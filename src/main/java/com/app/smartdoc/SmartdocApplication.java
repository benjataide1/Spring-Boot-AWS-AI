package com.app.smartdoc;

// SpringApplication: la clase que arranca todo Spring Boot
import org.springframework.boot.SpringApplication;

// La anotación más importante de Spring Boot — une 3 anotaciones en una
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Para scheduling (procesamiento periódico de la cola SQS)
import org.springframework.scheduling.annotation.EnableScheduling;

// Para caching con @Cacheable
import org.springframework.cache.annotation.EnableCaching;


/**
 * SmartDocApplication — Punto de entrada de SmartDoc AI Platform
 * ═══════════════════════════════════════════════════════════════
 *
 * Esta clase "enciende" todo el sistema.
 * Cuando ejecutás "mvn spring-boot:run", Java busca el método main()
 * y comienza desde acá.
 */

/**
 * @SpringBootApplication — La anotación madre de Spring Boot
 * ══════════════════════════════════════════════════════════
 * Es una combinación de 3 anotaciones:
 *
 * 1. @SpringBootConfiguration
 *    → Esta clase es una fuente de configuración de Spring.
 *
 * 2. @EnableAutoConfiguration
 *    → Spring Boot configura automáticamente todo lo que encuentra
 *      en el classpath (las librerías del pom.xml).
 *    Ejemplo: encuentra Tomcat → lo configura en el puerto 8080
 *             encuentra Jackson → lo configura para serializar JSON
 *             encuentra OpenAI starter → configura el ChatClient
 *             encuentra pgvector starter → configura el VectorStore
 *
 * 3. @ComponentScan
 *    → Escanea el paquete "com.app.smartdoc" y todos sus sub-paquetes.
 *    → Busca clases con @Service, @Controller, @Repository, @Configuration.
 *    → Las instancia y administra como "beans" de Spring.
 *
 * ¿Qué es un bean?
 *    Un bean es un objeto que Spring crea, configura y administra.
 *    Vos NO hacés "new S3Service()". Spring lo crea y lo inyecta
 *    donde lo necesités. Esto se llama "Inversión de Control" (IoC).
 *    Beneficio: bajo acoplamiento, fácil de testear, fácil de reemplazar.
 */

@SpringBootApplication
@EnableScheduling    // Habilita el scheduler para el polling de SQS
@EnableCaching       // Habilita el sistema de caché con @Cacheable
public class SmartdocApplication {
    /**
     * main() — Método que Java busca para iniciar la ejecución
     *
     * SpringApplication.run() realiza TODO este trabajo:
     *   1. Crea el "contexto de Spring" (el contenedor de beans)
     *   2. Lee application.yml y carga la configuración
     *   3. Escanea y crea todos los beans (@Service, @Controller, etc.)
     *   4. Configura Spring AI (ChatClient, EmbeddingModel, VectorStore)
     *   5. Configura los clientes AWS (S3Client, SqsClient, etc.)
     *   6. Arranca el servidor Tomcat en el puerto 8080
     *   7. La API queda disponible en http://localhost:8080
     *
     * El proceso típicamente tarda 5-10 segundos la primera vez.
     */
    public static void main(String[] args) {
        SpringApplication.run(SmartdocApplication.class, args);

        // Banner de bienvenida — ayuda a confirmar que la app arrancó bien
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════╗
            ║   🚀  SmartDoc AI Platform — ONLINE                         ║
            ║                                                              ║
            ║   API:       http://localhost:8080                           ║
            ║   Swagger:   http://localhost:8080/swagger-ui.html           ║
            ║   Health:    http://localhost:8080/actuator/health           ║
            ║                                                              ║
            ║   Stack: Spring Boot 3.3 + Spring AI 1.0 + AWS SDK v2      ║
            ╚══════════════════════════════════════════════════════════════╝
            """);
    }

}
