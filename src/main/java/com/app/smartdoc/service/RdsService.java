package com.app.smartdoc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Este servicio es útil para:
 * - Listar instancias RDS y verificar su estado
 * - Tomar snapshots manuales antes de un deploy
 * - Monitorear el tamaño del almacenamiento
 * - Automatizar operaciones de mantenimiento
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RdsService {

    private final RdsClient rdsClient;

    /**
     * Lista todas las instancias RDS de la cuenta con su estado actual.
     * Útil para verificar que la BD está "available" antes de un deploy.
     */
    public List<Map<String, String>> listarInstancias() {
        log.info("Listando instancias RDS...");

        DescribeDbInstancesResponse response = rdsClient.describeDBInstances(
                DescribeDbInstancesRequest.builder().build()
        );

        return response.dbInstances().stream()
                .map(db -> Map.of(
                        // El identificador único de la instancia (el nombre que elegiste al crear)
                        "identifier", db.dbInstanceIdentifier(),
                        // El engine: "postgres", "mysql", "mariadb", "oracle-ee", etc.
                        "engine", db.engine() + " " + db.engineVersion(),
                        // Estado actual: "available", "creating", "modifying", "stopping", "stopped"
                        "estado", db.dbInstanceStatus(),
                        // El hostname al que tu app se conecta (el mismo que va en DATABASE_URL)
                        "endpoint", db.endpoint() != null ? db.endpoint().address() : "no disponible",
                        // La clase de instancia: "db.t3.micro", "db.m5.large", etc.
                        "clase", db.dbInstanceClass(),
                        // Almacenamiento en GB
                        "almacenamientoGB", db.allocatedStorage().toString()
                ))
                .collect(Collectors.toList());
    }


    /**
     * Toma un snapshot manual de la BD.
     * Usar antes de un deploy importante o migración de datos.
     *
     * Un snapshot de RDS es una copia point-in-time de la BD completa.
     * Se guarda en S3 (gestionado por AWS, no en tu bucket) y se puede
     * restaurar a una nueva instancia en minutos.
     *
     * Costo: $0.095 por GB/mes (los primeros 100% del tamaño de la BD son gratis)
     *
     * @param dbInstanceIdentifier  el nombre de la instancia RDS (ej: "smartdoc-db")
     * @param snapshotIdentifier    nombre del snapshot (ej: "smartdoc-pre-deploy-v2")
     */
    public String tomarSnapshot(String dbInstanceIdentifier, String snapshotIdentifier) {
        log.info("Tomando snapshot de {}: {}", dbInstanceIdentifier, snapshotIdentifier);

        CreateDbSnapshotResponse response = rdsClient.createDBSnapshot(
                CreateDbSnapshotRequest.builder()
                        .dbInstanceIdentifier(dbInstanceIdentifier)
                        // El identificador del snapshot debe ser único en tu cuenta
                        // Buena práctica: incluir la fecha y el motivo
                        // Ej: "smartdoc-db-2025-01-15-pre-deploy-v2"
                        .dbSnapshotIdentifier(snapshotIdentifier)
                        .build()
        );

        String snapshotArn = response.dbSnapshot().dbSnapshotArn();
        log.info("Snapshot iniciado: {} | Estado: {}",
                snapshotIdentifier, response.dbSnapshot().status());

        return snapshotArn;
    }


    /**
     * Verifica si la BD está disponible para recibir conexiones.
     * Útil en el health check de la aplicación.
     */
    public boolean estaDisponible(String dbInstanceIdentifier) {
        try {
            DescribeDbInstancesResponse response = rdsClient.describeDBInstances(
                    DescribeDbInstancesRequest.builder()
                            .dbInstanceIdentifier(dbInstanceIdentifier)
                            .build()
            );

            String estado = response.dbInstances().get(0).dbInstanceStatus();
            boolean disponible = "available".equals(estado);

            if (!disponible) {
                log.warn("RDS '{}' no está disponible. Estado actual: {}", dbInstanceIdentifier, estado);
            }

            return disponible;
        } catch (DbInstanceNotFoundException e) {
            log.error("Instancia RDS '{}' no encontrada", dbInstanceIdentifier);
            return false;
        }
    }
}

