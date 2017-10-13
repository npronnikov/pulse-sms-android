/*
 * Copyright (C) 2017 Luke Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.klinker.messenger.shared.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.VisibleForTesting;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.klinker.android.send_message.Message;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.Transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import xyz.klinker.messenger.api.implementation.Account;
import xyz.klinker.messenger.shared.data.FeatureFlags;
import xyz.klinker.messenger.shared.data.MimeType;
import xyz.klinker.messenger.shared.data.MmsSettings;
import xyz.klinker.messenger.shared.receiver.MmsSentReceiver;
import xyz.klinker.messenger.shared.receiver.SmsDeliveredReceiver;
import xyz.klinker.messenger.shared.receiver.SmsSentReceiver;
import xyz.klinker.messenger.shared.receiver.SmsSentReceiverNoRetry;

/**
 * Utility for helping to send messages.
 */
public class SendUtils {

    private Integer subscriptionId;
    private boolean forceNoSignature = false;
    private boolean forceSplitMessage = false;
    private boolean retryOnFailedMessages = true;

    public SendUtils() {
        this(null);
    }

    public SendUtils(Integer subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public SendUtils setForceNoSignature(boolean forceNoSignature) {
        this.forceNoSignature = forceNoSignature;
        return this;
    }

    public SendUtils setForceSplitMessage(boolean splitMessage) {
        this.forceSplitMessage = splitMessage;
        return this;
    }

    public SendUtils setRetryFailedMessages(boolean retry) {
        this.retryOnFailedMessages = retry;
        return this;
    }

    public void send(Context context, String text, String address) {
        send(context, text, address.split(", "), null, null);
    }

    public void send(Context context, String text, String[] addresses) {
        send(context, text, addresses, null, null);
    }

    public Uri send(Context context, String text, String addresses, Uri data,
                           String mimeType) {
        return send(context, text, addresses.split(", "), data, mimeType);
    }

    public Uri send(Context context, String text, String[] addresses, Uri data,
                           String mimeType) {
        if (FeatureFlags.INSTANCE.getNEVER_SEND_FROM_WATCH() && WearableCheck.isAndroidWear(context)) {
            return data;
        }

        xyz.klinker.messenger.shared.data.Settings appSettings = xyz.klinker.messenger.shared.data.Settings.get(context);
        if (!appSettings.signature.isEmpty() && !forceNoSignature) {
            text += "\n" + appSettings.signature;
        }

        MmsSettings mmsSettings = MmsSettings.get(context);
        Settings settings = new Settings();
        settings.setDeliveryReports(xyz.klinker.messenger.shared.data.Settings.get(context)
                .deliveryReports);
        settings.setSendLongAsMms(mmsSettings.convertLongMessagesToMMS);
        settings.setSendLongAsMmsAfter(mmsSettings.numberOfMessagesBeforeMms);
        settings.setGroup(mmsSettings.groupMMS);
        settings.setStripUnicode(xyz.klinker.messenger.shared.data.Settings.get(context)
                .stripUnicode);
        settings.setPreText(xyz.klinker.messenger.shared.data.Settings.get(context)
                .giffgaffDeliveryReports ? "*0#" : "");
        settings.setSplit(forceSplitMessage || shouldSplitMessages(context));

        if (mmsSettings.overrideSystemAPN) {
            settings.setUseSystemSending(false);

            settings.setMmsc(mmsSettings.mmscUrl);
            settings.setProxy(mmsSettings.mmsProxy);
            settings.setPort(mmsSettings.mmsPort);
            settings.setAgent(mmsSettings.userAgent);
            settings.setUserProfileUrl(mmsSettings.userAgentProfileUrl);
            settings.setUaProfTagName(mmsSettings.userAgentProfileTagName);
        }

        if (subscriptionId != null && subscriptionId != 0 && subscriptionId != -1) {
            settings.setSubscriptionId(subscriptionId);
        }

        Transaction transaction = new Transaction(context, settings);
        transaction.setExplicitBroadcastForDeliveredSms(new Intent(context, SmsDeliveredReceiver.class));
        transaction.setExplicitBroadcastForSentSms(new Intent(context, retryOnFailedMessages ? SmsSentReceiver.class : SmsSentReceiverNoRetry.class));
        transaction.setExplicitBroadcastForSentMms(new Intent(context, MmsSentReceiver.class));

        Message message = new Message(text, addresses);

        if (data != null) {
            try {
                Log.v("Sending MMS", "mime type: " + mimeType);
                if (MimeType.INSTANCE.isStaticImage(mimeType)) {
                    data = ImageUtils.scaleToSend(context, data, mimeType);

                    if (!mimeType.equals(MimeType.INSTANCE.getIMAGE_PNG())) {
                        mimeType = MimeType.INSTANCE.getIMAGE_JPEG();
                    }
                }

                byte[] bytes = getBytes(context, data);
                Log.v("Sending MMS", "size: " + bytes.length + " bytes, mime type: " + mimeType);
                message.addMedia(bytes, mimeType);
            } catch (NullPointerException | IOException | SecurityException e) {
                Log.e("Sending Exception", "Could not attach media: " + data, e);
            }
        }

        if (!Account.INSTANCE.exists() || Account.INSTANCE.getPrimary() ) {
            try {
                transaction.sendNewMessage(message, Transaction.NO_THREAD_ID);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (UnsupportedOperationException e) {
                // Sent from a Chromebook? How did they get to this point?
                e.printStackTrace();
            }
        }

        return data;
    }

    public  static byte[] getBytes(Context context, Uri data) throws IOException, NullPointerException, SecurityException {
        InputStream stream = context.getContentResolver().openInputStream(data);
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];

        int len = 0;
        while ((len = stream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }

        stream.close();

        return byteBuffer.toByteArray();
    }

    @VisibleForTesting
    public boolean shouldSplitMessages(Context context) {
        List<String> carrierDoesntAutoSplit = Arrays.asList("u.s. cellular");

        try {
            TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String carrierName = manager.getNetworkOperatorName();
            if (carrierDoesntAutoSplit.contains(carrierName.toLowerCase())) {
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
