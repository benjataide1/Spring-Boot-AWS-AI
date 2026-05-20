package com.app.smartdoc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;

/**
 * S3Service — Lógica para interactuar con Amazon S3
 * ══════════════════════════════════════════════════
 *
 * Responsabilidades:
 * 1. Subir archivos (PDFs) al bucket
 * 2. Generar URLs firmadas para que los usuarios descarguen sus archivos
 * 3. Eliminar archivos cuando el usuario borra un documento
 *
 * Estructura de keys en S3:
 * "documents/{userId}/{uuid}-{nombreOriginal}"
 * Ejemplo: "documents/user123/7f3a2b1c-contrato-venta.pdf"
 *
 * El {uuid} evita colisiones si dos usuarios suben archivos con el mismo nombre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    // El cliente S3 creado en AwsConfig — Spring lo inyecta automáticamente
    private final S3Client s3Client;

    // Nombre del bucket leído del application.yml
    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    // La región leída del application.yml (necesaria para el pre-signer)
    @Value("${aws.region}")
    private String region;

    /**
     * Sube un archivo al bucket S3.
     *
     * ¿Cómo funciona internamente?
     * 1. Tu app construye el request con el archivo y los metadatos
     * 2. El SDK de AWS hace un request HTTP multipart a S3
     * 3. S3 guarda el archivo y responde con un ETag (hash del archivo)
     * 4. Retornamos la key (ruta) del objeto en S3 — la guardamos en la DB
     *
     * @param archivo  el archivo subido por el usuario via REST API
     * @param userId   ID del usuario dueño del archivo
     * @return         la key del objeto en S3 (la ruta dentro del bucket)
     */
    public String subirArchivo(MultipartFile archivo, String userId) throws IOException {
        // Generamos una key única para el objeto
        // UUID previene colisiones si dos archivos tienen el mismo nombre
        String key = "documents/" + userId + "/" + UUID.randomUUID() + "-" + archivo.getOriginalFilename();

        log.info("Subiendo archivo a S3: bucket={}, key={}, size={}KB",
                bucketName, key, archivo.getSize() / 1024);

        // PutObjectRequest: "formulario" para subir un objeto a S3
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)                          // en qué bucket
                .key(key)                                    // con qué nombre/ruta
                .contentType(archivo.getContentType())       // tipo MIME (ej: application/pdf)
                .contentLength(archivo.getSize())            // tamaño en bytes
                // Metadatos adicionales — los podés consultar sin descargar el archivo
                .metadata(java.util.Map.of(
                        "original-filename", archivo.getOriginalFilename(),
                        "user-id", userId,
                        "uploaded-at", java.time.Instant.now().toString()
                ))
                .build();

        // RequestBody.fromInputStream(): lee el archivo del request HTTP y lo envía a S3
        // Sin cargar todo en memoria — eficiente para archivos grandes
        s3Client.putObject(request, RequestBody.fromInputStream(
                archivo.getInputStream(),
                archivo.getSize()
        ));

        log.info("Archivo subido exitosamente: s3://{}/{}", bucketName, key);
        return key; // devolvemos la key para guardarla en la base de datos
    }


    /**
     * Genera una URL firmada (pre-signed URL) para descargar un archivo.
     *
     * ¿Para qué sirven las URLs firmadas?
     * - El bucket es privado (no accesible al público)
     * - Pero a veces queremos dar acceso temporal a un archivo específico
     * - La URL firmada tiene una firma criptográfica de AWS que la valida
     * - Tiene expiración: después de X minutos, la URL deja de funcionar
     *
     * Caso de uso: el usuario pide descargar su PDF. Generamos una URL
     * firmada que expira en 15 minutos. El navegador del usuario descarga
     * el archivo directamente de S3 sin pasar por tu servidor.
     *
     * @param key         la key del objeto en S3
     * @param duracion    cuánto tiempo es válida la URL
     * @return            URL firmada para descargar el archivo
     */
    public URL generarUrlFirmada(String key, Duration duracion) {
        // S3Presigner: cliente especial solo para generar URLs firmadas
        // Lo creamos local para este método (no necesitamos bean global)
        // Crear el presigner con la región correcta
        // S3Presigner.create() usaría la región por defecto del entorno
        // S3Presigner.builder().region() es explícito y más robusto
        try (S3Presigner presigner = S3Presigner.builder()
                .region(software.amazon.awssdk.regions.Region.of(region))
                .build()) {

            // GetObjectPresignRequest: request para generar la URL firmada
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(duracion)  // cuánto dura la URL (ej: 15 minutos)
                    .getObjectRequest(            // qué objeto de S3 queremos
                            GetObjectRequest.builder()
                                    .bucket(bucketName)
                                    .key(key)
                                    .build()
                    )
                    .build();

            URL url = presigner.presignGetObject(presignRequest).url();
            log.info("URL firmada generada para key: {} (válida por {})", key, duracion);
            return url;
        }
    }


    /**
     * Elimina un archivo de S3.
     * Se llama cuando el usuario borra un documento.
     */
    public void eliminarArchivo(String key) {
        log.info("Eliminando archivo de S3: bucket={}, key={}", bucketName, key);
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build()
        );
        log.info("Archivo eliminado: {}", key);
    }

    /**
     * Verifica que el bucket existe y es accesible.
     * Usado en el health check de la aplicación.
     */
    public boolean verificarBucket() {
        try {
            s3Client.headBucket(
                    HeadBucketRequest.builder().bucket(bucketName).build()
            );
            return true; // Si no lanzó excepción, el bucket existe y es accesible
        } catch (Exception e) {
            log.error("Error al verificar el bucket S3: {}", e.getMessage());
            return false;
        }
    }
}
