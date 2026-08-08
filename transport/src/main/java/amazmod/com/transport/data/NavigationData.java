package amazmod.com.transport.data;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.huami.watch.transport.DataBundle;

import amazmod.com.transport.Transportable;

/**
 * Turn-by-turn navigation data extracted from the Google Maps ongoing notification on the phone
 * and sent to the watch.
 *
 * The turn icon is sent as a PNG byte array but only when it actually changes: the phone keeps
 * the hash of the last icon it sent and sends an empty icon with the same hash whenever the watch
 * already has it cached (see NavigationActivity's icon cache on the watch side).
 */
public class NavigationData extends Transportable implements Parcelable {

    public static final String EXTRA = "navigation";

    private static final String NEXT_ROAD = "nextRoad";
    private static final String NEXT_ROAD_DESCRIPTION = "nextRoadDescription";
    private static final String DISTANCE_TO_NEXT = "distanceToNext";
    private static final String TOTAL_DISTANCE = "totalDistance";
    private static final String ETA = "eta";
    private static final String ETE = "ete";
    private static final String ICON = "icon";
    private static final String ICON_HASH = "iconHash";
    private static final String REROUTING = "rerouting";
    private static final String TIMESTAMP = "timestamp";
    private static final String VIBRATION = "vibration";
    private static final String SCREEN_ON = "screenOn";
    private static final String KEEP_SCREEN_ON = "keepScreenOn";
    private static final String PROGRESS_PERCENT = "progressPercent";
    private static final String BEARING = "bearing";
    private static final String TRAFFIC_AHEAD = "trafficAhead";
    private static final String SHOW_HEART_RATE = "showHeartRate";
    private static final String AUTO_BRIGHTNESS = "autoBrightness";
    private static final String SPEED = "speed";

    // "Turn right onto", the road name in the Maps notification
    private String nextRoad;
    // Secondary line of the instruction, eg. "towards Jl. Sudirman"
    private String nextRoadDescription;
    // Distance until the next manoeuvre, eg. "450 m"
    private String distanceToNext;
    // Remaining distance of the whole trip, eg. "12 km"
    private String totalDistance;
    // Arrival clock time, eg. "20:45"
    private String eta;
    // Remaining time, eg. "23 min"
    private String ete;
    // PNG of the manoeuvre arrow, empty when the watch already cached this iconHash
    private byte[] icon;
    private String iconHash;
    // True while Maps says "Rerouting…" (no structured data available)
    private boolean rerouting;
    private long timestamp;
    private int vibration;
    private boolean screenOn;
    // Hold the watch display on for the whole trip rather than just at each manoeuvre
    private boolean keepScreenOn;
    // How far along the route we are, 0-100, or -1 when Maps did not say
    private int progressPercent;
    // Compass bearing the instruction names, degrees from north, or -1 when it names none
    private int bearing;
    // Congestion close enough ahead to matter, already formatted, or empty
    private String trafficAhead;
    // Watch-side extras the phone owner switched on; they cost battery, so they are opt-in
    private boolean showHeartRate;
    private boolean autoBrightness;
    // Current speed, already formatted, or empty when unknown or switched off
    private String speed;

    public NavigationData() {
        this.nextRoad = "";
        this.nextRoadDescription = "";
        this.distanceToNext = "";
        this.totalDistance = "";
        this.eta = "";
        this.ete = "";
        this.iconHash = "";
        this.icon = new byte[0];
        this.rerouting = false;
        this.timestamp = 0;
        this.vibration = 0;
        this.screenOn = false;
        this.keepScreenOn = false;
        this.progressPercent = -1;
        this.bearing = -1;
        this.trafficAhead = "";
        this.showHeartRate = false;
        this.autoBrightness = false;
        this.speed = "";
    }

    protected NavigationData(Parcel in) {
        nextRoad = in.readString();
        nextRoadDescription = in.readString();
        distanceToNext = in.readString();
        totalDistance = in.readString();
        eta = in.readString();
        ete = in.readString();
        icon = in.createByteArray();
        iconHash = in.readString();
        rerouting = in.readByte() != 0;
        timestamp = in.readLong();
        vibration = in.readInt();
        screenOn = in.readByte() != 0;
        keepScreenOn = in.readByte() != 0;
        progressPercent = in.readInt();
        bearing = in.readInt();
        trafficAhead = in.readString();
        showHeartRate = in.readByte() != 0;
        autoBrightness = in.readByte() != 0;
        speed = in.readString();
    }

    public static final Creator<NavigationData> CREATOR = new Creator<NavigationData>() {
        @Override
        public NavigationData createFromParcel(Parcel in) {
            return new NavigationData(in);
        }

        @Override
        public NavigationData[] newArray(int size) {
            return new NavigationData[size];
        }
    };

    @Override
    public DataBundle toDataBundle(DataBundle dataBundle) {

        dataBundle.putString(NEXT_ROAD, nextRoad);
        dataBundle.putString(NEXT_ROAD_DESCRIPTION, nextRoadDescription);
        dataBundle.putString(DISTANCE_TO_NEXT, distanceToNext);
        dataBundle.putString(TOTAL_DISTANCE, totalDistance);
        dataBundle.putString(ETA, eta);
        dataBundle.putString(ETE, ete);
        dataBundle.putByteArray(ICON, icon);
        dataBundle.putString(ICON_HASH, iconHash);
        dataBundle.putBoolean(REROUTING, rerouting);
        dataBundle.putLong(TIMESTAMP, timestamp);
        dataBundle.putInt(VIBRATION, vibration);
        dataBundle.putBoolean(SCREEN_ON, screenOn);
        dataBundle.putBoolean(KEEP_SCREEN_ON, keepScreenOn);
        dataBundle.putInt(PROGRESS_PERCENT, progressPercent);
        dataBundle.putInt(BEARING, bearing);
        dataBundle.putString(TRAFFIC_AHEAD, trafficAhead);
        dataBundle.putBoolean(SHOW_HEART_RATE, showHeartRate);
        dataBundle.putBoolean(AUTO_BRIGHTNESS, autoBrightness);
        dataBundle.putString(SPEED, speed);

        return dataBundle;
    }

    public static NavigationData fromDataBundle(DataBundle dataBundle) {
        NavigationData navigationData = new NavigationData();

        navigationData.setNextRoad(dataBundle.getString(NEXT_ROAD));
        navigationData.setNextRoadDescription(dataBundle.getString(NEXT_ROAD_DESCRIPTION));
        navigationData.setDistanceToNext(dataBundle.getString(DISTANCE_TO_NEXT));
        navigationData.setTotalDistance(dataBundle.getString(TOTAL_DISTANCE));
        navigationData.setEta(dataBundle.getString(ETA));
        navigationData.setEte(dataBundle.getString(ETE));
        navigationData.setIcon(dataBundle.getByteArray(ICON));
        navigationData.setIconHash(dataBundle.getString(ICON_HASH));
        navigationData.setRerouting(dataBundle.getBoolean(REROUTING));
        navigationData.setTimestamp(dataBundle.getLong(TIMESTAMP));
        navigationData.setVibration(dataBundle.getInt(VIBRATION));
        navigationData.setScreenOn(dataBundle.getBoolean(SCREEN_ON));
        navigationData.setKeepScreenOn(dataBundle.getBoolean(KEEP_SCREEN_ON));
        navigationData.setProgressPercent(dataBundle.getInt(PROGRESS_PERCENT));
        navigationData.setBearing(dataBundle.getInt(BEARING));
        navigationData.setTrafficAhead(dataBundle.getString(TRAFFIC_AHEAD));
        navigationData.setShowHeartRate(dataBundle.getBoolean(SHOW_HEART_RATE));
        navigationData.setAutoBrightness(dataBundle.getBoolean(AUTO_BRIGHTNESS));
        navigationData.setSpeed(dataBundle.getString(SPEED));

        return navigationData;
    }

    public static NavigationData fromBundle(Bundle bundle) {
        return bundle.getParcelable(EXTRA);
    }

    @Override
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA, this);
        return bundle;
    }

    public String getNextRoad() {
        return nextRoad == null ? "" : nextRoad;
    }

    public void setNextRoad(String nextRoad) {
        this.nextRoad = nextRoad;
    }

    public String getNextRoadDescription() {
        return nextRoadDescription == null ? "" : nextRoadDescription;
    }

    public void setNextRoadDescription(String nextRoadDescription) {
        this.nextRoadDescription = nextRoadDescription;
    }

    public String getDistanceToNext() {
        return distanceToNext == null ? "" : distanceToNext;
    }

    public void setDistanceToNext(String distanceToNext) {
        this.distanceToNext = distanceToNext;
    }

    public String getTotalDistance() {
        return totalDistance == null ? "" : totalDistance;
    }

    public void setTotalDistance(String totalDistance) {
        this.totalDistance = totalDistance;
    }

    public String getEta() {
        return eta == null ? "" : eta;
    }

    public void setEta(String eta) {
        this.eta = eta;
    }

    public String getEte() {
        return ete == null ? "" : ete;
    }

    public void setEte(String ete) {
        this.ete = ete;
    }

    public byte[] getIcon() {
        return icon;
    }

    public void setIcon(byte[] icon) {
        this.icon = icon == null ? new byte[0] : icon;
    }

    public String getIconHash() {
        return iconHash == null ? "" : iconHash;
    }

    public void setIconHash(String iconHash) {
        this.iconHash = iconHash;
    }

    public boolean isRerouting() {
        return rerouting;
    }

    public void setRerouting(boolean rerouting) {
        this.rerouting = rerouting;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getVibration() {
        return vibration;
    }

    public void setVibration(int vibration) {
        this.vibration = vibration;
    }

    public boolean isScreenOn() {
        return screenOn;
    }

    public void setScreenOn(boolean screenOn) {
        this.screenOn = screenOn;
    }

    public boolean isKeepScreenOn() {
        return keepScreenOn;
    }

    public void setKeepScreenOn(boolean keepScreenOn) {
        this.keepScreenOn = keepScreenOn;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public int getBearing() {
        return bearing;
    }

    public void setBearing(int bearing) {
        this.bearing = bearing;
    }

    public String getTrafficAhead() {
        return trafficAhead == null ? "" : trafficAhead;
    }

    public void setTrafficAhead(String trafficAhead) {
        this.trafficAhead = trafficAhead;
    }

    public boolean isShowHeartRate() {
        return showHeartRate;
    }

    public void setShowHeartRate(boolean showHeartRate) {
        this.showHeartRate = showHeartRate;
    }

    public boolean isAutoBrightness() {
        return autoBrightness;
    }

    public void setAutoBrightness(boolean autoBrightness) {
        this.autoBrightness = autoBrightness;
    }

    public String getSpeed() {
        return speed == null ? "" : speed;
    }

    public void setSpeed(String speed) {
        this.speed = speed;
    }

    /**
     * Text-only signature of the trip state, used by the phone to avoid re-sending identical data.
     * The icon is deliberately left out: it is keyed by iconHash which is part of this signature.
     */
    public String getSignature() {
        return getNextRoad() + "|" + getNextRoadDescription() + "|" + getDistanceToNext() + "|"
                + getTotalDistance() + "|" + getEta() + "|" + getEte() + "|" + getIconHash() + "|"
                + isRerouting();
    }

    /**
     * True when the parser found nothing usable, in which case the caller should fall back to
     * sending the Maps notification as a plain notification.
     */
    public boolean isEmpty() {
        return getNextRoad().isEmpty() && getDistanceToNext().isEmpty() && getEta().isEmpty();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(nextRoad);
        dest.writeString(nextRoadDescription);
        dest.writeString(distanceToNext);
        dest.writeString(totalDistance);
        dest.writeString(eta);
        dest.writeString(ete);
        dest.writeByteArray(icon);
        dest.writeString(iconHash);
        dest.writeByte((byte) (rerouting ? 1 : 0));
        dest.writeLong(timestamp);
        dest.writeInt(vibration);
        dest.writeByte((byte) (screenOn ? 1 : 0));
        dest.writeByte((byte) (keepScreenOn ? 1 : 0));
        dest.writeInt(progressPercent);
        dest.writeInt(bearing);
        dest.writeString(trafficAhead);
        dest.writeByte((byte) (showHeartRate ? 1 : 0));
        dest.writeByte((byte) (autoBrightness ? 1 : 0));
        dest.writeString(speed);
    }

    @Override
    public String toString() {
        return "NavigationData{road='" + getNextRoad() + "', desc='" + getNextRoadDescription()
                + "', dist='" + getDistanceToNext() + "', total='" + getTotalDistance()
                + "', eta='" + getEta() + "', ete='" + getEte() + "', iconHash='" + getIconHash()
                + "', iconBytes=" + (icon == null ? 0 : icon.length) + ", rerouting=" + rerouting + "}";
    }
}
