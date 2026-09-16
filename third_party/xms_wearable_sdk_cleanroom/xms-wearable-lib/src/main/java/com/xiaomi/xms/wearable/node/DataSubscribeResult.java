package com.xiaomi.xms.wearable.node;

public class DataSubscribeResult {
    public static final int RESULT_CONNECTION_CONNECTED = 1;
    public static final int RESULT_CONNECTION_DISCONNECTED = 2;
    public static final int RESULT_CHARGING_START = 1;
    public static final int RESULT_CHARGING_QUIT = 2;
    public static final int RESULT_CHARGING_FINISH = 3;
    public static final int RESULT_WEARING_ON = 1;
    public static final int RESULT_WEARING_OFF = 2;
    public static final int RESULT_SLEEP_IN = 1;
    public static final int RESULT_SLEEP_OUT = 2;
    public static final int RESULT_WARNING_HEART_RATE_HIGH = 1;
    public static final int RESULT_WARNING_HEART_RATE_LOW = 2;
    public static final int RESULT_WARNING_ACTIVE_HEART_RATE_HIGH = 3;
    public static final int RESULT_WARNING_ACTIVE_HEART_RATE_LOW = 4;

    private int connectedStatus;
    private int sleepStatus;
    private int wearingStatus;
    private int warningStatus;
    private int chargingStatus;

    public int getConnectedStatus() {
        return connectedStatus;
    }

    public void setConnectedStatus(int connectedStatus) {
        this.connectedStatus = connectedStatus;
    }

    public int getSleepStatus() {
        return sleepStatus;
    }

    public void setSleepStatus(int sleepStatus) {
        this.sleepStatus = sleepStatus;
    }

    public int getWearingStatus() {
        return wearingStatus;
    }

    public void setWearingStatus(int wearingStatus) {
        this.wearingStatus = wearingStatus;
    }

    public int getWarningStatus() {
        return warningStatus;
    }

    public void setWarningStatus(int warningStatus) {
        this.warningStatus = warningStatus;
    }

    public int getChargingStatus() {
        return chargingStatus;
    }

    public void setChargingStatus(int chargingStatus) {
        this.chargingStatus = chargingStatus;
    }
}
