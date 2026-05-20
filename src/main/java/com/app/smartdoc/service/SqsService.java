package com.app.smartdoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

/**
 * En nuestro sistema, SQS tiene este rol:
 * - Cuando el usuario sube un documento → publicamos un mensaje en la cola
 * - El worker procesa el mensaje → llama al DocumentIngestionService
 * - SQS garantiza que el mensaje no se pierde aunque el worker falle
 *
 * Garantía "at-least-once delivery":
 *   SQS garantiza que cada mensaje se entrega AL MENOS UNA VEZ.
 *   (Puede entregarse más de una vez en casos de falla — tu código
 *   debe manejar mensajes duplicados, es decir, ser "idempotente")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqsService {

    private final SqsClient sqsClient;

    // ObjectMapper de Jackson: convierte objetos Java ↔ JSON
    // Spring Boot crea automáticamente este bean
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    /**
     * Publica un mensaje en la cola SQS.
     *
     * Usamos este record para estructurar el mensaje.
     * Un record de Java es una clase inmutable compacta — ideal para DTOs.
     */
    public record DocumentoSubidoEvent(
            Long documentId,      // ID del registro en la BD
            String s3Key,         // ruta del archivo en S3
            String userId,        // quién subió el documento
            String filename       // nombre original del archivo
    ) {}


    /**
     * Publica un evento "documento subido" en la cola SQS.
     *
     * El mensaje viaja como JSON en la cola.
     * El worker lo deserializa y lo procesa.
     *
     * @param event  el evento con los datos del documento subido
     */
    public void publicarDocumentoSubido(DocumentoSubidoEvent event) {
        try {
            // Convertimos el objeto Java a String JSON para enviarlo en el mensaje
            String cuerpoMensaje = objectMapper.writeValueAsString(event);

            log.info("Publicando en SQS: {}", cuerpoMensaje);

            // SendMessageRequest: "formulario" para enviar un mensaje a la cola
            SendMessageResponse response = sqsClient.sendMessage(
                    SendMessageRequest.builder()
                            .queueUrl(queueUrl)          // a cuál cola enviar
                            .messageBody(cuerpoMensaje)  // el contenido (JSON String)
                            // delaySeconds: cuántos segundos esperar antes de que
                            // el mensaje sea visible para los consumidores
                            // 0 = disponible inmediatamente
                            .delaySeconds(0)
                            .build()
            );

            // messageId: ID único del mensaje en SQS (útil para tracking)
            log.info("Mensaje publicado en SQS. MessageId: {}", response.messageId());

        } catch (JsonProcessingException e) {
            // JsonProcessingException: el objeto no se pudo serializar a JSON
            log.error("Error al serializar el mensaje SQS: {}", e.getMessage());
            throw new RuntimeException("Error al publicar mensaje en SQS", e);
        }
    }

    /**
     * Lee mensajes de la cola (polling).
     *
     * ¿Qué es polling?
     * SQS no "empuja" mensajes a tu app — tu app debe "preguntar" periódicamente.
     * Usamos @Scheduled para hacer esto automáticamente cada 5 segundos.
     *
     * Long Polling: el parámetro waitTimeSeconds le dice a SQS que espere
     * hasta 20 segundos antes de responder si no hay mensajes.
     * Ventaja: reduce las llamadas vacías y el costo asociado.
     *
     * @return lista de mensajes SQS recibidos (puede estar vacía)
     */
    public List<Message> recibirMensajes() {
        ReceiveMessageResponse response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        // maxNumberOfMessages: máximo de mensajes por llamada (máximo: 10)
                        .maxNumberOfMessages(5)
                        // waitTimeSeconds: long polling — espera hasta 20 segundos
                        // Reduce llamadas innecesarias cuando la cola está vacía
                        .waitTimeSeconds(20)
                        // visibilityTimeout: durante cuántos segundos el mensaje es
                        // "invisible" para otros consumidores mientras lo procesamos.
                        // Si tu app falla sin confirmar, el mensaje vuelve a ser visible.
                        // Para procesamiento de AI que puede tardar: 5 minutos
                        .visibilityTimeout(300)
                        .build()
        );

        return response.messages();
    }

    /**
     * Confirma que un mensaje fue procesado (lo borra de la cola).
     *
     * ¿Por qué hay que borrar el mensaje manualmente?
     * SQS no borra el mensaje cuando lo entrega — lo hace invisible.
     * Si tu app falla antes de procesarlo, el mensaje vuelve a ser visible
     * y otro consumidor puede procesarlo.
     * Cuando terminaste de procesar exitosamente, borrás el mensaje.
     * Este patrón se llama "acknowledge" o "ack".
     *
     * @param receiptHandle  token único del mensaje recibido
     *                       (viene en el objeto Message)
     */
    public void confirmarMensaje(String receiptHandle) {
        sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(receiptHandle) // token de esta recepción específica
                        .build()
        );
        log.debug("Mensaje confirmado y eliminado de la cola");
    }
}
