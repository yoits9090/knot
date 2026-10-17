import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import processing.data.*;

/** Real ECCC station records. Direction is meteorological FROM, converted to scene flow. */
final class WeatherData {
    static final class Observation {
        final String utc; final float speed,from,east,south;
        Observation(String utc,float speed,float from){this.utc=utc;this.speed=speed;this.from=from;double r=Math.toRadians(from);east=(float)(-speed*Math.sin(r));south=(float)(speed*Math.cos(r));}
        float angle(){return (float)Math.atan2(south,east);}
        String label(){return utc.replace('T',' ').replace(":00:00Z","Z")+String.format(Locale.ROOT," | %.1f m/s from %.0f°",speed,from);}
        boolean stale(){try{return Instant.parse(utc).isBefore(Instant.now().minus(Duration.ofHours(3)));}catch(Exception e){return true;}}
    }
    final List<Observation> history=new ArrayList<>();
    final JSONObject backtest;
    Observation current;
    int index;
    volatile String status="Cached ECCC observations";
    WeatherData(){
        try{
            for(String line:Files.readAllLines(Paths.get("data/weather/observations.csv"))){if(line.startsWith("utc,"))continue;String[] f=line.split(",");history.add(new Observation(f[0],Float.parseFloat(f[1]),Float.parseFloat(f[2])));}
            if(history.isEmpty())throw new IllegalStateException("No valid observations");
            index=history.size()-1;current=history.get(index);
            backtest=JSONObject.parse(Files.readString(Paths.get("data/weather/backtest.json")));
        }catch(Exception e){throw new IllegalStateException("Missing weather cache. Run python3 scripts/cache-weather.py",e);}
    }
    Observation previous(){index=(index-1+history.size())%history.size();current=history.get(index);status="Historical observation";return current;}
    static Observation fetchLatest() throws Exception {
        String uri="https://api.weather.gc.ca/collections/climate-hourly/items?f=json&limit=48&STATION_NAME=TORONTO%20CITY%20CENTRE&sortby=-UTC_DATE";
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r=client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(25)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new java.io.IOException("ECCC HTTP "+r.statusCode());
        JSONArray features=JSONObject.parse(r.body()).getJSONArray("features");
        for(int i=0;i<features.size();i++){
            JSONObject p=features.getJSONObject(i).getJSONObject("properties");
            if(p.isNull("WIND_SPEED")||!p.isNull("WIND_SPEED_FLAG")||!p.isNull("WIND_DIRECTION_FLAG"))continue;
            float speed=p.getFloat("WIND_SPEED")/3.6f;
            if(speed>0&&p.isNull("WIND_DIRECTION"))continue;
            return new Observation(p.getString("UTC_DATE")+"Z",speed,p.isNull("WIND_DIRECTION")?0:p.getFloat("WIND_DIRECTION")*10);
        }throw new java.io.IOException("No valid wind in latest station response");
    }
    String[] backtestLines(){
        JSONObject p=backtest.getJSONObject("metrics").getJSONObject("persistence"),a=backtest.getJSONObject("metrics").getJSONObject("ar1");
        return new String[]{"Wind input backtest | ECCC Toronto City Centre",backtest.getInt("observation_count")+" observations | chronological 70/30 split | one-hour horizon",
            "Held out: "+backtest.getString("test_start_utc")+" to "+backtest.getString("test_end_utc"),
            String.format(Locale.ROOT,"Persistence: speed MAE %.2f m/s | vector RMSE %.2f m/s | direction MAE %.1f°",p.getDouble("speed_mae_ms"),p.getDouble("vector_rmse_ms"),p.getDouble("direction_mae_deg")),
            String.format(Locale.ROOT,"Fitted AR(1): speed MAE %.2f m/s | vector RMSE %.2f m/s | direction MAE %.1f°",a.getDouble("speed_mae_ms"),a.getDouble("vector_rmse_ms"),a.getDouble("direction_mae_deg")),
            "Station input accuracy only. Street-level CFD and altitude winds are unvalidated.","R: previous observed hour   L: fetch latest available   B: close"};
    }
}
