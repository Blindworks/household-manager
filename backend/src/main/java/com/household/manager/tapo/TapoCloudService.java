package com.household.manager.tapo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TapoCloudService {

    // Die offizielle TP-Link Cloud API Gateway URL
    private static final String CLOUD_URL = "https://wap.tplinkcloud.com";
    private final String username;
    private final String password;
    private final String terminalUUID;
    private final HttpClient httpClient;

    private String token;

    public TapoCloudService(String username, String password) {
        this.username = username;
        this.password = password;
        // Die Cloud erwartet eine eindeutige UUID für die "App" (unser Skript)
        this.terminalUUID = UUID.randomUUID().toString();
        this.httpClient = HttpClient.newBuilder().build();
    }

    public boolean loginToCloud() {
        try {
            System.out.println("1. Verbinde mit dem TP-Link Tapo Cloud Service...");

            // Der offizielle TP-Link Login-Payload
            String payload = String.format(
                    "{\"method\":\"login\",\"params\":{\"appType\":\"Tapo_Android\",\"cloudUserName\":\"%s\",\"cloudPassword\":\"%s\",\"terminalUUID\":\"%s\"}}",
                    username, password, terminalUUID
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLOUD_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            // Extrahiere den Cloud-Token aus der Antwort
            this.token = extractJsonValue(responseBody, "token");

            if (this.token != null) {
                System.out.println("-> Erfolgreich in der Tapo Cloud eingeloggt!");
                return true;
            } else {
                System.err.println("-> Login fehlgeschlagen. Antwort vom Server:\n" + responseBody);
                return false;
            }
        } catch (Exception e) {
            System.err.println("Fehler beim Cloud-Login: " + e.getMessage());
            return false;
        }
    }

    public void fetchAvailableDevices() {
        if (this.token == null) {
            System.out.println("Bitte zuerst einloggen!");
            return;
        }

        try {
            System.out.println("2. Rufe verfügbare Geräte aus deinem Account ab...");

            // Befehl, um alle mit dem Account verknüpften Geräte abzufragen
            String payload = "{\"method\":\"getDeviceList\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLOUD_URL + "?token=" + this.token))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("\n=== DEINE VERFÜGBAREN TAPO GERÄTE ===");
            // Die rohe JSON-Antwort leicht formatieren, damit du die Geräteblöcke besser lesen kannst
            String formattedOutput = response.body().replace("},{", "}\n\n{");
            System.out.println(formattedOutput);
            System.out.println("=====================================");

        } catch (Exception e) {
            System.err.println("Fehler beim Abrufen der Geräte: " + e.getMessage());
        }
    }

    // Kleiner Hilfsextraktor, damit wir keine externen JSON-Bibliotheken brauchen
    private String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // --- NEUE METHODE ZUM STEUERN ---
    public String sendCommandToDevice(String deviceId, String commandJson) {
        if (this.token == null) {
            System.out.println("Bitte zuerst einloggen!");
            return null;
        }

        try {
            // Wir müssen das innere JSON escapen, damit die Cloud es als String ans Gerät weiterleitet
            String escapedCommand = commandJson.replace("\"", "\\\"");

            String payload = String.format(
                    "{\"method\":\"passthrough\",\"params\":{\"deviceId\":\"%s\",\"requestData\":\"%s\"}}",
                    deviceId, escapedCommand
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLOUD_URL + "?token=" + this.token))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Antwort vom Gerät:");
            System.out.println(response.body());
            return response.body();

        } catch (Exception e) {
            System.err.println("Fehler beim Senden des Befehls: " + e.getMessage());
            return null;
        }
    }

    // --- START ---
    public static void main(String[] args) {
        String user = "benedikt.lind@gmail.com"; // HIER TAPO EMAIL EINTRAGEN
        String pass = "taxcRH51#";    // HIER TAPO PASSWORT EINTRAGEN

        /*TapoCloudService cloudService = new TapoCloudService(user, pass);

        if (cloudService.loginToCloud()) {
            cloudService.fetchAvailableDevices();
        }*/

        TapoCloudService cloudService = new TapoCloudService(user, pass);

        if (cloudService.loginToCloud()) {

            // Die Device-ID deiner HS100 Steckdose "A1 Mini Keller"
            String targetDeviceId = "80066C2C4EEC2E59565671C8A6F0FDD4196C210F";

            // Befehl zum EINSCHALTEN (Kasa-Syntax für HS100)
            String turnOnCommand = "{\"system\":{\"set_relay_state\":{\"state\":1}}}";

            // Befehl zum AUSSCHALTEN
            String turnOffCommand = "{\"system\":{\"set_relay_state\":{\"state\":0}}}";

            // Status abfragen
            String getInfoCommand = "{\"system\":{\"get_sysinfo\":{}}}";

            System.out.println("\nSchalte 'A1 Mini Keller' ein...");
            cloudService.sendCommandToDevice(targetDeviceId, turnOnCommand);

            // Zum Ausschalten einfach diese Zeile entkommentieren:
            // cloudService.sendCommandToDevice(targetDeviceId, turnOffCommand);
        }
    }
}