package com.aomega08.keephodling;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Timer;
import java.util.TimerTask;

public class BuyEvent extends BroadcastReceiver {
    Persistence persistence;
    Preferences preferences;
    GdaxApi gdaxApi;
    NotificationManager notificationManager;
    Context context;

    @Override
    public void onReceive(final Context context, Intent intent) {
        persistence = new Persistence(context);
        preferences = new Preferences(context);
        gdaxApi = new GdaxApi(context);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.context = context;

        // Ensure we can buy
        if (!persistence.getAutobuyEnabled()) {
            return;
        }

        try {
            assertTimePassed();

            gdaxApi.buy(spendAmount(), new GdaxApi.Listener() {
                @Override
                public void onSuccess(JsonNode response) {
                    checkFill(response.get("id").asText());
                }

                @Override
                public void onFailure(String message) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                }
            });

            persistence.setLastBuyTime(System.currentTimeMillis());
        } catch (Exception e) {
            Log.e("BUY", e.getMessage());
        }
    }

    void sendNotification(String title, String content) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context)
                                                    .setContentTitle(title)
                                                    .setSmallIcon(R.drawable.btc)
                                                    .setContentText(content);

        notificationManager.notify((int) (Math.random() * 512), mBuilder.build());
    }

    void sendSuccessNotification(double spent, String bought) {
        NumberFormat formatter = new DecimalFormat("#0.00");
        String spentString = formatter.format(spent);

        sendNotification("Bought " + bought + " " + preferences.getCryptoCurrency(), "Paid " + spentString + " " + preferences.getBaseCurrency());
    }

    void sendFailureNotification(String reason) {
        sendNotification("Failed to buy " + preferences.getCryptoCurrency(), "Last order status: " + reason);
    }

    public double spendAmount() {
        String period = preferences.getFrequency();
        double monthAmount = preferences.getAmount();

        double amount;

        switch (period) {
            case "hourly":
                amount = monthAmount / 30.0 / 24.0;
                break;
            case "daily":
                amount = monthAmount / 30.0;
                break;
            case "weekly":
                amount = monthAmount / 4.33333;
                break;
            case "monthly":
                return monthAmount;
            default:
                return 0.0;
        }

        return Math.floor(amount * 100) / 100;
    }

    private void assertTimePassed() throws Exception {
        long lastTime = persistence.getLastBuyTime();
        long diff;

        switch (preferences.getFrequency()) {
            case "hourly":
                diff = 3600 * 1000;
                break;
            case "daily":
                diff = 3600 * 24 * 1000;
                break;
            case "weekly":
                diff = 3600 * 24 * 7 * 1000;
                break;
            case "monthly":
                long secondsPerMonth = 3600 * 24 * 30;
                diff = secondsPerMonth * 1000;
                break;
            default:
                throw new Exception("Invalid frequency");
        }

        // Allow up to 5 minutes of alarm drift
        if (System.currentTimeMillis() - lastTime < diff - 5 * 60 * 1000) {
            throw new Exception("Assertion failed. Not enough time passed since last operation");
        }
    }

    private void checkFill(final String order_id) {
        final Timer t = new Timer();
        t.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                gdaxApi.getOrder(order_id, new GdaxApi.Listener() {
                    @Override
                    public void onSuccess(JsonNode response) {
                        if (response.get("status").asText().equals("done")) {
                            t.cancel();
                            t.purge();

                            String reason = response.get("done_reason").asText();
                            if (reason.equals("filled")) {
                                sendSuccessNotification(response.get("specified_funds").asDouble(), response.get("filled_size").asText());
                            } else {
                                sendFailureNotification(reason);
                            }
                        }
                    }

                    @Override
                    public void onFailure(String message) {

                    }
                });
            }
        }, 200, 2000);
    }
}