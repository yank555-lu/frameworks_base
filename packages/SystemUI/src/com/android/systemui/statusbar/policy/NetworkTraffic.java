package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;

/*
*
* Seeing how an Integer object in java requires at least 16 Bytes, it seemed awfully wasteful
* to only use it for a single boolean. 32-bits is plenty of room for what we need it to do.
*
*/
public class NetworkTraffic extends TextView implements StatusIconDisplayable {

    public static final String SLOT = "networktraffic";

    public static final int MASK_UP = 0x00000001;        // Least valuable bit
    public static final int MASK_DOWN = 0x00000002;      // Second least valuable bit
    public static final int MASK_UNIT = 0x00000004;      // Third least valuable bit
    public static final int MASK_PERIOD = 0xFFFF0000;    // Most valuable 16 bits
    public static final int DEFAULT_ICON_TINT = Color.WHITE;

    private static final int KILOBIT = 1000;
    private static final int KILOBYTE = 1024;

    private int mIconTint = DEFAULT_ICON_TINT;

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private int mState = 0;
    private int mStateAnimation = 0;
    private boolean mIsEnabled;
    private boolean mAttached;
    private long totalRxBytes;
    private long totalTxBytes;
    private long lastUpdateTime;
    private int KB = KILOBIT;
    private int MB = KB * KB;
    private int GB = MB * KB;
    private boolean mAutoHide;
    private int mAutoHideThreshold;
    private boolean mAnimateArrows;
    private int mVisibleState = -1;
    private boolean mTrafficVisible = false;
    private boolean mSystemIconVisible = true;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < getInterval(mState) * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            if (mAnimateArrows) {
                int stateAnimation = mStateAnimation;

                mStateAnimation = (txData == 0 ? 0 : MASK_UP)
                        + (rxData == 0 ? 0 : MASK_DOWN);

                if (stateAnimation != mStateAnimation) {
                    updateTrafficDrawable();
                }
            }

            if (shouldHide(rxData, txData, timeDelta)) {
                setText("");
                mTrafficVisible = false;
            } else {
                // If bit/s convert from Bytes to bits
                String symbol;
                if (KB == KILOBYTE) {
                    symbol = "B/s";
                } else {
                    symbol = "b/s";
                    rxData = rxData * 8;
                    txData = txData * 8;
                }

                // Get information for uplink ready so the line return can be added
                String output = "";
                if (isSet(mState, MASK_UP)) {
                    output = formatOutput(timeDelta, txData, symbol);
                }

                // Go to new line in case we show both
                if (isSet(mState, MASK_UP + MASK_DOWN)) {
                    output += "\n";
                }

                // Add information for downlink if it's called for
                if (isSet(mState, MASK_DOWN)) {
                    output += formatOutput(timeDelta, rxData, symbol);
                }

                // Update view if there's anything new to show
                if (! output.contentEquals(getText())) {
                    // Ensure text size is where it needs to be
                    updateTextSize();
                    setText(output);
                }
                mTrafficVisible = true;
            }
            updateVisibility();

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, getInterval(mState));
        }

        private String formatOutput(long timeDelta, long data, String symbol) {
            long speed = (long)(data / (timeDelta / 1000F));
            if (speed < KB) {
                return decimalFormat.format(speed) + symbol;
            } else if (speed < MB) {
                return decimalFormat.format(speed / (float)KB) + 'k' + symbol;
            } else if (speed < GB) {
                return decimalFormat.format(speed / (float)MB) + 'M' + symbol;
            }
            return decimalFormat.format(speed / (float)GB) + 'G' + symbol;
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KILOBYTE;
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KILOBYTE;
            int mState = 2;
                return mAutoHide &&
                   (mState == MASK_DOWN && speedRxKB <= mAutoHideThreshold ||
                    mState == MASK_UP && speedTxKB <= mAutoHideThreshold ||
                    mState == MASK_UP + MASK_DOWN &&
                       speedRxKB <= mAutoHideThreshold &&
                       speedTxKB <= mAutoHideThreshold);
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            Uri uri = Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_STATE);
            resolver.registerContentObserver(uri, false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_AUTOHIDE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_ANIMATE_ARROWS), false,
                    this, UserHandle.USER_ALL);
        }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        updateIconTint(DarkIconDispatcher.getTint(area, this, tint));
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateSettings();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mAutoHide = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE, 0,
                UserHandle.USER_CURRENT) == 1;

        mAutoHideThreshold = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10,
                UserHandle.USER_CURRENT);

        mAnimateArrows = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_ANIMATE_ARROWS, 0,
                UserHandle.USER_CURRENT) == 1;

        mState = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_STATE, 0);
        if (isSet(mState, MASK_UNIT)) {
            KB = KILOBYTE;
        } else {
            KB = KILOBIT;
        }
        MB = KB * KB;
        GB = MB * KB;

        if (isSet(mState, MASK_UP) || isSet(mState, MASK_DOWN)) {
            if (getConnectAvailable()) {
                if (mAttached) {
                    totalRxBytes = TrafficStats.getTotalRxBytes();
                    lastUpdateTime = SystemClock.elapsedRealtime();
                    mTrafficHandler.sendEmptyMessage(1);
                }
                mIsEnabled = true;
                updateTrafficDrawable();
                updateVisibility();
                return;
            }
        } else {
            clearHandlerCallbacks();
        }
        mIsEnabled = false;
        updateVisibility();
    }

    private static boolean isSet(int intState, int intMask) {
        return (intState & intMask) == intMask;
    }

    private static int getInterval(int intState) {
        int intInterval = intState >>> 16;
        return (intInterval >= 250 && intInterval <= 32750) ? intInterval : 1000;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    private void updateTrafficDrawable() {

        int intTrafficDrawable;
        int state;
        Drawable trafficIcon = null;

        state = (mAnimateArrows && isSet(mState, MASK_UP + MASK_DOWN)) ? mStateAnimation : mState;

        if (isSet(state, MASK_UP + MASK_DOWN)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_updown;
        } else if (isSet(state, MASK_UP)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
        } else if (isSet(state, MASK_DOWN)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_down;
        } else {
            intTrafficDrawable = mAnimateArrows ? R.drawable.stat_sys_network_traffic_none : 0;
        }
        if (intTrafficDrawable != 0) {
            trafficIcon = getResources().getDrawable(intTrafficDrawable);
            trafficIcon.setTint(mIconTint);
        }
        setCompoundDrawablesWithIntrinsicBounds(null, null, trafficIcon, null);
    }

    public void updateIconTint(int iconTint) {
        mIconTint = iconTint;
        setTextColor(mIconTint);
        updateTrafficDrawable();
    }

    public void updateTextSize() {

        final int txtSizeSingle =
                getResources().getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        final int txtSizeMulti =
                getResources().getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);

        if (isSet(mState, MASK_UP + MASK_DOWN)) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)txtSizeMulti);
        } else {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)txtSizeSingle);
        }
    }

    @Override
    public String getSlot() {
        return SLOT;
    }

    @Override
    public boolean isIconVisible() {
        return mIsEnabled;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mSystemIconVisible = true;
                break;
            case STATE_DOT:
            case STATE_HIDDEN:
            default:
                mSystemIconVisible = false;
                break;
        }
        updateVisibility();
    }

    private void updateVisibility() {
        if (mIsEnabled && mTrafficVisible && mSystemIconVisible) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mIconTint = color;
        setTextColor(mIconTint);
        updateTrafficDrawable();
    }

    @Override
    public void setDecorColor(int color) {
    }
}
