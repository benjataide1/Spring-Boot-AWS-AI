package com.app.smartdoc.service;

// Importaciones de Lombok para reducir boilerplate
import lombok.RequiredArgsConstructor; // Genera constructor con campos final
import lombok.extern.slf4j.Slf4j;     // Genera: Logger log = LoggerFactory.getLogger(...)

// Spring: anotación para registrar esta clase como servicio
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// AWS SDK: todas las clases para interactuar con EC2
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ec2Service — Lógica de negocio para gestionar instancias EC2
 * ═════════════════════════════════════════════════════════════
 *
 * Posición en la arquitectura:
 *   Controller → Service (acá) → AWS SDK → AWS
 *
 * @Service le dice a Spring que esta clase es un bean de servicio.
 * Spring la instancia UNA vez y la inyecta donde la pidan.
 *
 * @RequiredArgsConstructor (Lombok) genera automáticamente:
 *   public Ec2Service(Ec2Client ec2Client) {
 *       this.ec2Client = ec2Client;
 *   }
 * Spring llama a ese constructor e inyecta el Ec2Client del AwsConfig.
 */
@Slf4j               // Genera el logger: uso log.info(), log.error(), etc.
@Service             // Bean de Spring de capa de servicio
@RequiredArgsConstructor // Constructor injection de todos los campos final
public class Ec2Service {

    // final: garantiza que el campo no cambia y fuerza la inyección por constructor
    private final Ec2Client ec2Client;

    // Lee los valores por defecto del application.yml
    @Value("${aws.ec2.default-ami:ami-0c02fb55956c7d316}")
    private String defaultAmi;  // AMI de Amazon Linux 2023 en us-east-1

    @Value("${aws.ec2.default-key-pair:mi-clave-java}")
    private String defaultKeyPair;

    /**
     * Lanza una nueva instancia EC2.
     *
     * ¿Qué pasa internamente?
     * 1. Spring Boot llama al SDK de AWS
     * 2. El SDK hace un request HTTPS a la API de EC2 en us-east-1
     * 3. AWS verifica las credenciales, valida los parámetros
     * 4. AWS crea la instancia y la pone en estado "pending"
     * 5. En 1-2 minutos, la instancia pasa a "running"
     *
     * @param tipoInstancia  el tipo de instancia (ej: "t2.micro", "c5.xlarge")
     * @param nombre         nombre descriptivo (se guarda como tag "Name")
     * @return               el ID de la instancia creada (ej: "i-0a1b2c3d4e5f67890")
     */
    public String lanzarInstancia(String tipoInstancia, String nombre) {
        log.info("Lanzando instancia EC2: tipo={}, nombre={}", tipoInstancia, nombre);

        // RunInstancesRequest: el "formulario" de creación de instancia
        RunInstancesRequest request = RunInstancesRequest.builder()
                // AMI (Amazon Machine Image): el "disco de instalación" con el SO
                // ami-0c02fb55956c7d316 = Amazon Linux 2023 en us-east-1
                .imageId(defaultAmi)

                // Tipo de instancia: define CPU y RAM
                .instanceType(InstanceType.fromValue(tipoInstancia))

                // minCount/maxCount: cuántas instancias lanzar
                // Para lanzar exactamente 1, ambos deben ser 1
                .minCount(1)
                .maxCount(1)

                // Key pair: para conectarse por SSH
                // Debe existir en AWS con este nombre exacto
                .keyName(defaultKeyPair)

                // Tags: metadatos clave-valor para organizar tus recursos
                // El tag "Name" es especial: es el nombre que aparece en la consola
                .tagSpecifications(
                        TagSpecification.builder()
                                .resourceType(ResourceType.INSTANCE) // aplicar el tag a la instancia
                                .tags(Tag.builder()
                                        .key("Name")
                                        .value(nombre)
                                        .build())
                                .build()
                )
                .build(); // construye el objeto request inmutable

        // ec2Client.runInstances() envía el request a AWS y devuelve la respuesta
        RunInstancesResponse response = ec2Client.runInstances(request);

        // La respuesta incluye la lista de instancias lanzadas
        // Como lanzamos exactamente 1 (minCount=maxCount=1), tomamos el primero
        String instanceId = response.instances().get(0).instanceId();

        log.info("Instancia lanzada exitosamente: {}", instanceId);
        return instanceId;
    }

    /**
     * Lista todas las instancias EC2 de la cuenta.
     * Retorna solo las que están en estado "running" o "stopped"
     * (excluye las "terminated" que ya no existen).
     */
    public List<String> listarInstancias() {
        log.info("Listando instancias EC2...");

        // DescribeInstancesRequest: solicita información sobre las instancias
        // Sin filtros → devuelve TODAS las instancias de la cuenta
        DescribeInstancesResponse response = ec2Client.describeInstances(
                DescribeInstancesRequest.builder().build()
        );

        // La respuesta viene en "reservations" (grupos de instancias lanzadas juntas)
        // Aplanamos la estructura: reservations → instances → instanceId
        List<String> instancias = response.reservations().stream()
                // flatMap: transforma cada reservation en su lista de instances
                .flatMap(r -> r.instances().stream())
                // Filtramos las terminadas (ya no existen físicamente)
                .filter(i -> !i.state().name().equals(InstanceStateName.TERMINATED))
                // Extraemos solo el instanceId de cada instancia
                .map(i -> i.instanceId() + " | " + i.state().name() + " | " + i.instanceType())
                .collect(Collectors.toList());

        log.info("Instancias encontradas: {}", instancias.size());
        return instancias;
    }



    /**
     * Detiene una instancia (equivale a "apagar" — guarda el estado).
     * La instancia puede reiniciarse después.
     * Costo: cuando está stopped, no se cobra por cómputo (sí por el disco).
     */
    public void detenerInstancia(String instanceId) {
        log.info("Deteniendo instancia: {}", instanceId);
        ec2Client.stopInstances(
                StopInstancesRequest.builder()
                        .instanceIds(instanceId) // puede recibir múltiples IDs
                        .build()
        );
        log.info("Solicitud de detención enviada para: {}", instanceId);
    }

    /**
     * Termina (elimina permanentemente) una instancia.
     * ⚠️ IRREVERSIBLE: los datos del disco se pierden a menos que uses EBS.
     */
    public void terminarInstancia(String instanceId) {
        log.warn("Terminando instancia: {} — ACCIÓN IRREVERSIBLE", instanceId);
        ec2Client.terminateInstances(
                TerminateInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build()
        );
    }

    /**
     * Determina la familia de la instancia a partir del tipo.
     * Ej: "c5.xlarge" → "Optimizada para cómputo"
     */
    public String obtenerFamilia(String tipoInstancia) {
        // El primer carácter del tipo define la familia
        char familia = tipoInstancia.charAt(0);
        return switch (familia) {
            case 't', 'm' -> "Uso general";
            case 'c' -> "Optimizada para cómputo";
            case 'r', 'x' -> "Optimizada para memoria";
            case 'p', 'g' -> "Cómputo acelerado (GPU)";
            case 'i', 'd' -> "Optimizada para almacenamiento";
            default -> "Desconocida";
        };
    }
}
