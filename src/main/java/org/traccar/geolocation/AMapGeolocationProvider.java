/*
 * Copyright 2023 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.geolocation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.traccar.config.Config;
import org.traccar.model.Network;
import org.traccar.model.CellTower;
import org.traccar.model.WifiAccessPoint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;

public class AMapGeolocationProvider implements GeolocationProvider {

    private static final String AMAP_API_URL = "https://restapi.amap.com/v5/position/IoT";
    private final String apiKey;

    public AMapGeolocationProvider(Config config) {
        this.apiKey = config.getString("geolocation.amap.key");
    }

    @Override
    public void getLocation(Network network, LocationProviderCallback callback) {
        try {
            JsonObject requestBody = buildRequestBody(network);
            String response = sendRequest(requestBody);
            Gson gson = new Gson();
            JsonObject result = gson.fromJson(response, JsonObject.class);
            if (result.get("status").getAsString().equals("1")
                    && result.has("position")) {
                String[] location = result.get("position").getAsString().split(",");
                double longitude = Double.parseDouble(location[0]);
                double latitude = Double.parseDouble(location[1]);
                int radius = result.has("radius") ? result.get("radius").getAsInt() : 0;
                callback.onSuccess(latitude, longitude, radius);
            } else {
                callback.onFailure(new GeolocationException(
                    result.has("info") ? result.get("info").getAsString() : "未知错误"
                ));
            }
        } catch (Exception e) {
            callback.onFailure(e);
        }
    }

    private JsonObject buildRequestBody(Network network) {
        JsonObject body = new JsonObject();
        body.addProperty("key", apiKey);
        Collection<WifiAccessPoint> wifiNetworks = network.getWifiAccessPoints();
        if (wifiNetworks != null && !wifiNetworks.isEmpty()) {
            body.addProperty("accesstype", 2);
            WifiAccessPoint mainWifi = wifiNetworks.iterator().next();
            body.addProperty("mmac", String.format("%s,%d,,-1",
                mainWifi.getMacAddress(),
                mainWifi.getSignalStrength()));
            if (wifiNetworks.size() > 1) {
                String additionalMacs = wifiNetworks.stream()
                    .skip(1)
                    .limit(29)
                    .map(wifi -> String.format("%s,%d,,-1",
                        wifi.getMacAddress(),
                        wifi.getSignalStrength()))
                    .collect(Collectors.joining("|"));
                body.addProperty("macs", additionalMacs);
            }
        }

        Collection<CellTower> cellNetworks = network.getCellTowers();
        if (cellNetworks != null && !cellNetworks.isEmpty()) {
            CellTower mainCell = cellNetworks.iterator().next();
            body.addProperty("accesstype", 1);
            body.addProperty("network", "GSM");
            body.addProperty("cdma",
                mainCell.getRadioType() != null
                && mainCell.getRadioType().equalsIgnoreCase("cdma") ? "1" : "0");
            String btsInfo = String.format("%d,%d,%d,%d,%d,0",
                mainCell.getMobileCountryCode(),
                mainCell.getMobileNetworkCode(),
                mainCell.getLocationAreaCode(),
                mainCell.getCellId(),
                mainCell.getSignalStrength() != null
                    ? mainCell.getSignalStrength() : 0);
            body.addProperty("bts", btsInfo);
        }
        return body;
    }

    private String sendRequest(JsonObject requestBody) throws Exception {
        URL url = new URL(AMAP_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                connection.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(requestBody.toString());
            writer.flush();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
