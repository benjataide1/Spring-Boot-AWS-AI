package com.app.smartdoc.config;

// @Value: inyecta valores del application.yml
import org.springframework.beans.factory.annotation.Value;
// @Bean: marca métodos cuyo retorno Spring registra como bean
import org.springframework.context.annotation.Bean;
// @Configuration: marca esta clase como fuente de configuración de Spring
import org.springframework.context.annotation.Configuration;

// Clientes del SDK de AWS para cada servicio
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * AwsConfig — Fábrica central de todos los clientes AWS
 * ══════════════════════════════════════════════════════
 *
 * RESPONSABILIDAD ÚNICA: crear los clientes del SDK de AWS y
 * registrarlos como beans de Spring para que otros servicios los inyecten.
 *
 * ¿Por qué una clase separada solo para esto?
 *   Principio de Responsabilidad Única (SRP) de SOLID:
 *   cada clase tiene UNA razón para existir.
 *   - La lógica de S3 va en S3Service
 *   - La configuración de clientes AWS va acá
 *   - Si mañana AWS cambia la forma de crear clientes, solo tocás esta clase
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  AUTENTICACIÓN — Default Credential Provider Chain              ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * NUNCA pongas Access Keys en el código Java.
 * El SDK de AWS las busca automáticamente en este orden:
 *
 *   1. Variables de entorno:
 *      AWS_ACCESS_KEY_ID y AWS_SECRET_ACCESS_KEY
 *
 *   2. Archivo ~/.aws/credentials (el que configuraste con "aws configure")
 *      [default]
 *      aws_access_key_id = AKIAIOSFODNN7EXAMPLE
 *      aws_secret_access_key = wJalrXUtnFEMI...
 *
 *   3. Rol IAM (cuando la app corre DENTRO de AWS: EC2, Lambda, ECS)
 *      En producción, la instancia EC2 tiene un rol IAM asignado y el SDK
 *      obtiene credenciales automáticamente sin archivos ni variables.
 *
 */
@Configuration  // Le dice a Spring que busque métodos @Bean en esta clase
public class AwsConfig {

    /*
     * @Value("${aws.region}")
     * Lee la propiedad "aws.region" del application.yml.
     *
     * Spring busca en el yml:
     *   aws:
     *     region: us-east-1   ← lee este valor y lo asigna a this.region
     *
     * Se asigna ANTES de que cualquier @Bean sea creado.
     */
    @Value("${aws.region}")
    private String region;

    /**
     * s3Client() — Bean para Amazon S3 (almacenamiento de archivos)
     * ──────────────────────────────────────────────────────────────
     *
     * @Bean le dice a Spring: "cuando alguien necesite un S3Client,
     * ejecutá este método y dales el objeto que devuelve".
     *
     * Es un SINGLETON: Spring crea UNA sola instancia y la reutiliza.
     * S3Client es thread-safe: múltiples requests pueden usarlo simultáneamente.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region)) // Region.of() convierte el String "us-east-1" al tipo Region
                .build();                  // build(): crea el cliente con la configuración especificada
    }

    /**
     * sqsClient() — Bean para Amazon SQS (colas de mensajes)
     * ────────────────────────────────────────────────────────
     * Para: enviar mensajes a la cola cuando se sube un documento,
     *       recibir y procesar mensajes en el worker.
     */
    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * snsClient() — Bean para Amazon SNS (notificaciones)
     * ──────────────────────────────────────────────────────
     * Para: publicar notificaciones cuando el procesamiento termina.
     */
    @Bean
    public SnsClient snsClient() {
        return SnsClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * ec2Client() — Bean para Amazon EC2 (instancias virtuales)
     * ───────────────────────────────────────────────────────────
     * Para: gestionar instancias EC2 programáticamente.
     */
    @Bean
    public Ec2Client ec2Client() {
        return Ec2Client.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * autoScalingClient() — Bean para EC2 Auto Scaling
     * ──────────────────────────────────────────────────
     * Para: crear y gestionar grupos de Auto Scaling.
     */
    @Bean
    public AutoScalingClient autoScalingClient() {
        return AutoScalingClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * elbClient() — Bean para Elastic Load Balancing v2 (Application LB)
     * ─────────────────────────────────────────────────────────────────────
     * "v2" porque soporta Application Load Balancer (el moderno).
     * La v1 es Classic LB (obsoleto — no usar en proyectos nuevos).
     */
    @Bean
    public ElasticLoadBalancingV2Client elbClient() {
        return ElasticLoadBalancingV2Client.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * stsClient() — Bean para Security Token Service
     * ─────────────────────────────────────────────────
     * Para: verificar credenciales en el health check.
     * stsClient.getCallerIdentity() → si responde, las creds son válidas.
     */
    @Bean
    public StsClient stsClient() {
        return StsClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * rdsClient() — Bean para Amazon RDS (gestión de infraestructura de BD)
     * ────────────────────────────────────────────────────────────────────────
     * Para: listar instancias, tomar snapshots, verificar estado.
     * NO es para hacer queries SQL — eso lo hace JDBC/JPA directamente.
     *
     * Requiere la dependencia en pom.xml:
     * <dependency>
     *   <groupId>software.amazon.awssdk</groupId>
     *   <artifactId>rds</artifactId>
     * </dependency>
     */
    @Bean
    public RdsClient rdsClient() {
        return RdsClient.builder()
                .region(Region.of(region))
                .build();
    }
}