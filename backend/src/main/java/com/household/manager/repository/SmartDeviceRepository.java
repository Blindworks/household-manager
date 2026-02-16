package com.household.manager.repository;

import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link SmartDevice} entity operations.
 * <p>
 * Provides data access methods for smart devices with support for
 * filtering by device type and external device identifiers.
 */
@Repository
public interface SmartDeviceRepository extends JpaRepository<SmartDevice, Long> {

    /**
     * Find all smart devices ordered by device type and device name.
     * <p>
     * Results are grouped by device type (alphabetically) and then sorted
     * by device name within each type.
     *
     * @return list of all smart devices, sorted by type and name
     */
    List<SmartDevice> findAllByOrderByDeviceTypeAscDeviceNameAsc();

    /**
     * Find all smart devices of a specific type, ordered by device name.
     *
     * @param deviceType the type of devices to retrieve
     * @return list of smart devices of the specified type, sorted by name
     */
    List<SmartDevice> findByDeviceTypeOrderByDeviceNameAsc(DeviceType deviceType);

    /**
     * Find a smart device by its type and external device ID.
     * <p>
     * The combination of device type and external device ID is unique,
     * allowing lookup of specific devices across scanning operations.
     *
     * @param deviceType the type of device
     * @param externalDeviceId the device's native identifier
     * @return optional containing the device if found, or empty if not found
     */
    Optional<SmartDevice> findByDeviceTypeAndExternalDeviceId(DeviceType deviceType, String externalDeviceId);

    /**
     * Check if a device exists with the given type and external device ID.
     * <p>
     * Useful for determining whether to create a new device or update
     * an existing one during scanning operations.
     *
     * @param deviceType the type of device
     * @param externalDeviceId the device's native identifier
     * @return true if a device exists with this type and ID, false otherwise
     */
    boolean existsByDeviceTypeAndExternalDeviceId(DeviceType deviceType, String externalDeviceId);
}
