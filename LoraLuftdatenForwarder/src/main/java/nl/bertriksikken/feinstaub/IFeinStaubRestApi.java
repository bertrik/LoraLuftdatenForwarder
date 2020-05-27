package nl.bertriksikken.feinstaub;

import nl.bertriksikken.luftdaten.dto.LuftdatenMessage;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface IFeinStaubRestApi {

    @POST("/data.php")
    Call<String> postData(@Header("X-Sensor") String sensorId, @Body LuftdatenMessage message);

}
