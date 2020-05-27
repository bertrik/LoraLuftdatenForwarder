package nl.bertriksikken.feinstaub;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.bertriksikken.luftdaten.dto.LuftdatenItem;
import nl.bertriksikken.luftdaten.dto.LuftdatenMessage;
import nl.bertriksikken.pm.ESensorItem;
import nl.bertriksikken.pm.SensorData;
import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

public final class FeinStaubUploader {

    private static final Logger LOG = LoggerFactory.getLogger(FeinStaubUploader.class);

    private final IFeinStaubRestApi restClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FeinStaubUploader(IFeinStaubRestApi restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    public static IFeinStaubRestApi newRestClient(String url, Duration timeout) {
        LOG.info("Creating new REST client for '{}' with timeout {}", url, timeout);

        OkHttpClient client = new OkHttpClient().newBuilder().callTimeout(timeout).build();
        Retrofit retrofit = new Retrofit.Builder().baseUrl(url).addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create()).client(client).build();
        return retrofit.create(IFeinStaubRestApi.class);
    }

    public void start() {
        LOG.info("Starting FeinStaubUploader");
    }

    public void stop() {
        LOG.info("Stopping FeinStaubUploader");
        executor.shutdown();
    }

    public void scheduleUpload(String deviceId, SensorData data) {
        LuftdatenMessage message = new LuftdatenMessage("LoraLuftdatenForwarder");

        // particulate matter
        if (data.hasValue(ESensorItem.PM10)) {
            message.addItem(new LuftdatenItem("SDS_P1", data.getValue(ESensorItem.PM10)));
        }
        if (data.hasValue(ESensorItem.PM2_5)) {
            message.addItem(new LuftdatenItem("SDS_P2", data.getValue(ESensorItem.PM2_5)));
        }

        // humidity/temperature/pressure
        if (data.hasValue(ESensorItem.HUMI)) {
            message.addItem(new LuftdatenItem("humidity", data.getValue(ESensorItem.HUMI)));
        }
        if (data.hasValue(ESensorItem.TEMP)) {
            message.addItem(new LuftdatenItem("temperature", data.getValue(ESensorItem.TEMP)));
        }
        if (data.hasValue(ESensorItem.PRESSURE)) {
            message.addItem(new LuftdatenItem("pressure", data.getValue(ESensorItem.PRESSURE)));
        }

        // schedule upload
        String luftdatenId = "TTN-" + deviceId;
        executor.execute(() -> uploadMeasurement(luftdatenId, message));
    }

    private void uploadMeasurement(String luftdatenId, LuftdatenMessage message) {
        LOG.info("Sending to FeinStaub for {}: {}", luftdatenId, message);
        try {
            Response<String> response = restClient.postData(luftdatenId, message).execute();
            if (response.isSuccessful()) {
                String result = response.body();
                LOG.info("Successfully posted to FeinStaub: {}", result);
            } else {
                LOG.warn("Failed to post to FeinStaub: {}", response.errorBody());
            }
        } catch (IOException e) {
            LOG.warn("Caught IOException: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("Caught exception: ", e);
        }
    }

}
