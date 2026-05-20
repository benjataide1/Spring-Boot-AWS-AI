package com.app.smartdoc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * En nuestro sistema:
 * Cuando el procesamiento de un documento termina → publicamos en SNS
 * → Los suscriptores son notificados:
 *   - Email al usuario: "Tu documento fue procesado"
 *   - SQS: otro sistema actualiza el estado
 *   - Lambda: indexa en Elasticsearch
 * Un solo publish → múltiples receptores → sin acoplamiento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnsService {

    private final SnsClient snsClient;

    @Value("${aws.sns.topic-arn}")
    private String topicArn; // ARN del tema SNS al que publicamos

    /**
     * Publica una notificación en el tema SNS.
     *
     * @param asunto  el asunto del mensaje (visible en email)
     * @param mensaje el cuerpo del mensaje (puede ser JSON)
     * @return        el messageId asignado por SNS
     */
    public String publicar(String asunto, String mensaje) {
        log.info("Publicando en SNS: asunto='{}'", asunto);

        PublishResponse response = snsClient.publish(
                PublishRequest.builder()
                        .topicArn(topicArn)   // a cuál tema publicar
                        .subject(asunto)      // asunto (visible en emails)
                        .message(mensaje)     // cuerpo del mensaje
                        .build()
        );

        log.info("Publicado en SNS. MessageId: {}", response.messageId());
        return response.messageId();
    }

    /**
     * Notifica que un documento fue procesado exitosamente.
     * Conveniencia sobre el método genérico publicar().
     */
    public void notificarDocumentoProcesado(Long documentId, String userId, String filename) {
        String asunto = "Documento procesado: " + filename;
        String mensaje = String.format(
                """
                {
                  "tipo": "DOCUMENTO_PROCESADO",
                  "documentId": %d,
                  "userId": "%s",
                  "filename": "%s",
                  "procesadoEn": "%s",
                  "mensaje": "Tu documento fue procesado. Ya podés hacer preguntas sobre él."
                }
                """,
                documentId, userId, filename, java.time.Instant.now()
        );
        publicar(asunto, mensaje);
    }

    /**
     * Notifica que ocurrió un error en el procesamiento.
     * Recibe el documentoId como String porque en el momento del error
     * puede ser un ID numérico ("42") o "desconocido" si falló antes de parsear el evento.
     *
     * @param documentoId  ID del documento o "desconocido"
     * @param error        mensaje de error para diagnóstico
     */
    public void notificarErrorProcesamiento(String documentoId, String error) {
        publicar(
                "Error al procesar documento",
                String.format(
                        "{\"tipo\":\"ERROR_PROCESAMIENTO\",\"documentoId\":\"%s\",\"error\":\"%s\"}",
                        documentoId, error
                )
        );
    }
}
