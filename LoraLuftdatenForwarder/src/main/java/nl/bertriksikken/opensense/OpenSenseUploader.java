package nl.bertriksikken.opensense;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.bertriksikken.loraforwarder.AttributeMap;
import nl.bertriksikken.luftdaten.dto.LuftdatenMessage;
import nl.bertriksikken.pm.ESensorItem;
import nl.bertriksikken.pm.SensorData;
import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

public final class OpenSenseUploader {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSenseUploader.class);

    private final IOpenSenseRestApi restClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, String> boxIds = new ConcurrentHashMap<>();

    OpenSenseUploader(IOpenSenseRestApi restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    public static OpenSenseUploader create(OpenSenseConfig config) {
        LOG.info("Creating new REST client for '{}' with timeout {}", config.getUrl(), config.getTimeoutSec());

        OkHttpClient client = new OkHttpClient().newBuilder().callTimeout(Duration.ofSeconds(config.getTimeoutSec()))
                .build();
        Retrofit retrofit = new Retrofit.Builder().baseUrl(config.getUrl())
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create()).client(client).build();
        IOpenSenseRestApi restClient = retrofit.create(IOpenSenseRestApi.class);
        return new OpenSenseUploader(restClient);
    }

    public void start() {
        LOG.info("Starting OpenSenseUploader");
    }

    public void stop() {
        LOG.info("Stopping OpenSenseUploader");
        executor.shutdown();
    }

    public void scheduleUpload(String deviceId, SensorData data) {
        String boxId = boxIds.getOrDefault(deviceId, "");
        if (boxId.isBlank()) {
            return;
        }

        LuftdatenMessage message = new LuftdatenMessage();

        // particulate matter
        String pmPrefix = getPmPrefix(data);
        if (data.hasValue(ESensorItem.PM10)) {
            message.addItem(pmPrefix + "P1", data.getValue(ESensorItem.PM10));
        }
        if (data.hasValue(ESensorItem.PM2_5)) {
            message.addItem(pmPrefix + "P2", data.getValue(ESensorItem.PM2_5));
        }
        if (data.hasValue(ESensorItem.PM1_0)) {
            message.addItem(pmPrefix + "P0", data.getValue(ESensorItem.PM2_5));
        }
        if (data.hasValue(ESensorItem.PM4_0)) {
            message.addItem(pmPrefix + "P4", data.getValue(ESensorItem.PM4_0));
        }

        // humidity/temperature/pressure
        String meteoPrefix = getMeteoPrefix(data);
        if (data.hasValue(ESensorItem.HUMI)) {
            message.addItem(meteoPrefix + "humidity", data.getValue(ESensorItem.HUMI));
        }
        if (data.hasValue(ESensorItem.TEMP)) {
            message.addItem(meteoPrefix + "temperature", data.getValue(ESensorItem.TEMP));
        }
        if (data.hasValue(ESensorItem.PRESSURE)) {
            message.addItem(meteoPrefix + "pressure", data.getValue(ESensorItem.PRESSURE));
        }

        // schedule upload
        String luftdatenId = "TTN-" + deviceId;
        executor.execute(() -> uploadMeasurement(boxId, luftdatenId, message));
    }

    private String getMeteoPrefix(SensorData data) {
        if (data.hasValue(ESensorItem.HUMI) && data.hasValue(ESensorItem.TEMP) && data.hasValue(ESensorItem.PRESSURE)) {
            return "BME280_";
        }
        return "";
    }

    private String getPmPrefix(SensorData data) {
        if (data.hasValue(ESensorItem.PM10) && data.hasValue(ESensorItem.PM2_5) && data.hasValue(ESensorItem.PM1_0)
                && data.hasValue(ESensorItem.PM4_0)) {
            return "SPS30_";
        }
        if (data.hasValue(ESensorItem.PM10) && data.hasValue(ESensorItem.PM2_5) && data.hasValue(ESensorItem.PM1_0)) {
            return "PMS_";
        }
        return "SDS_";
    }

    private void uploadMeasurement(String boxId, String luftdatenId, LuftdatenMessage message) {
        LOG.info("Upload for {} to opensense box {}: {}", luftdatenId, boxId, message);
        try {
            Response<String> response = restClient.postNewMeasurements(boxId, true, message).execute();
            if (response.isSuccessful()) {
                String result = response.body();
                LOG.info("Upload for {} to opensense box {} success: {}", luftdatenId, boxId, result);
            } else {
                LOG.warn("Upload for {} to opensense box {} failure: {}", luftdatenId, boxId,
                        response.errorBody().string());
            }
        } catch (IOException e) {
            LOG.warn("Caught IOException: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("Caught exception: ", e);
        }
    }

    public void processAttributes(Map<String, AttributeMap> attributes) {
        Map<String, String> newBoxIds = new HashMap<>();
        attributes.forEach((dev, attr) -> processDeviceAttributes(newBoxIds, dev, attr));
        boxIds.clear();
        boxIds.putAll(newBoxIds);
        boxIds.forEach((device, id) -> LOG.info("Opensense mapping: {} -> {}", device, id));
    }

    private void processDeviceAttributes(Map<String, String> map, String device, AttributeMap attributes) {
        String opensenseId = attributes.getOrDefault("opensense-id", "");
        if (!opensenseId.isBlank()) {
            map.put(device, opensenseId);
        }
    }

}
