package com.example.contactimporter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements ContactAdapter.Listener {
    private static final int REQ_OPEN_XLSX = 1001;
    private static final int REQ_CREATE_CSV = 1002;
    private static final int REQ_CONTACT_PERMISSION = 2001;

    private ContactDbHelper db;
    private ContactAdapter adapter;
    private TextView tvStats;
    private EditText etSearch;
    private Spinner spFilter;
    private String currentKeyword = "";
    private int currentFilterStatus = -1;
    private int pendingBatchCount = 0;

    private final String[] filterLabels = {"全部", "未导入", "已导入", "导入失败", "号码异常", "重复号码"};
    private final int[] filterStatuses = {-1, Contact.STATUS_PENDING, Contact.STATUS_IMPORTED, Contact.STATUS_FAILED, Contact.STATUS_INVALID, Contact.STATUS_DUPLICATE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new ContactDbHelper(this);
        adapter = new ContactAdapter(this);

        tvStats = findViewById(R.id.tvStats);
        etSearch = findViewById(R.id.etSearch);
        spFilter = findViewById(R.id.spFilter);
        ListView listView = findViewById(R.id.listContacts);
        listView.setAdapter(adapter);

        setupFilter();
        setupButtons();
        setupSearch();
        refresh();

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void setupButtons() {
        Button btnImportExcel = findViewById(R.id.btnImportExcel);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnExport = findViewById(R.id.btnExport);
        Button btnReset = findViewById(R.id.btnReset);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnBatch5 = findViewById(R.id.btnBatch5);
        Button btnBatch10 = findViewById(R.id.btnBatch10);
        Button btnBatch20 = findViewById(R.id.btnBatch20);
        Button btnBatch50 = findViewById(R.id.btnBatch50);

        btnImportExcel.setOnClickListener(v -> openXlsxPicker());
        btnAdd.setOnClickListener(v -> showEditDialog(null));
        btnExport.setOnClickListener(v -> createCsvFile());
        btnReset.setOnClickListener(v -> confirm("确认将已导入/导入失败的数据重置为未导入？", () -> {
            db.resetImportedToPending();
            refresh();
            toast("已重置");
        }));
        btnClear.setOnClickListener(v -> confirm("确认清空软件内所有联系人数据？此操作不会删除手机通讯录里已写入的联系人。", () -> {
            db.clearAll();
            refresh();
            toast("已清空");
        }));

        btnBatch5.setOnClickListener(v -> importNextBatch(5));
        btnBatch10.setOnClickListener(v -> importNextBatch(10));
        btnBatch20.setOnClickListener(v -> importNextBatch(20));
        btnBatch50.setOnClickListener(v -> importNextBatch(50));
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentKeyword = s == null ? "" : s.toString();
                refreshList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupFilter() {
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, filterLabels);
        spFilter.setAdapter(spAdapter);
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentFilterStatus = filterStatuses[position];
                refreshList();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void refresh() {
        refreshStats();
        refreshList();
    }

    private void refreshStats() {
        ContactDbHelper.Stats s = db.getStats();
        tvStats.setText("总数：" + s.total +
                "  ｜ 未导入：" + s.pending +
                "  ｜ 已导入：" + s.imported +
                "\n号码异常：" + s.invalid +
                "  ｜ 重复号码：" + s.duplicate +
                "  ｜ 导入失败：" + s.failed);
    }

    private void refreshList() {
        if (adapter == null || db == null) return;
        adapter.setData(db.getContacts(currentKeyword, currentFilterStatus));
    }

    private void openXlsxPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try {
            startActivityForResult(intent, REQ_OPEN_XLSX);
        } catch (Exception e) {
            Intent alt = new Intent(Intent.ACTION_GET_CONTENT);
            alt.addCategory(Intent.CATEGORY_OPENABLE);
            alt.setType("*/*");
            startActivityForResult(Intent.createChooser(alt, "选择 .xlsx 文件"), REQ_OPEN_XLSX);
        }
    }

    private void createCsvFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "联系人导入记录_" + System.currentTimeMillis() + ".csv");
        startActivityForResult(intent, REQ_CREATE_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQ_OPEN_XLSX) {
            importXlsx(uri);
        } else if (requestCode == REQ_CREATE_CSV) {
            exportCsv(uri);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) uri = (Uri) extra;
        }
        if (uri != null) importXlsx(uri);
    }

    private void importXlsx(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("无法读取文件");
                return;
            }
            String sourceFile = getDisplayName(uri);
            List<XlsxSimpleReader.RowData> rows = XlsxSimpleReader.readFirstSheetAB(in);
            int total = 0, inserted = 0, invalid = 0, duplicate = 0;
            Set<String> seenInThisFile = new HashSet<>();

            for (XlsxSimpleReader.RowData row : rows) {
                total++;
                String name = row.name == null ? "" : row.name.trim();
                String originalPhone = row.phone == null ? "" : row.phone.trim();
                String normalized = normalizePhone(originalPhone);

                if (name.isEmpty() && !normalized.isEmpty()) name = normalized;
                if (name.isEmpty() && normalized.isEmpty()) continue;

                Contact c = new Contact();
                c.name = name;
                c.phone = originalPhone;
                c.normalizedPhone = normalized;
                c.sourceFile = sourceFile;
                c.remark = "";
                c.importedAt = "";

                if (!isValidChinaMobile(normalized)) {
                    c.status = Contact.STATUS_INVALID;
                    invalid++;
                } else if (seenInThisFile.contains(normalized) || db.phoneExists(normalized)) {
                    c.status = Contact.STATUS_DUPLICATE;
                    duplicate++;
                } else {
                    c.status = Contact.STATUS_PENDING;
                    inserted++;
                    seenInThisFile.add(normalized);
                }
                db.insert(c);
            }
            refresh();
            new AlertDialog.Builder(this)
                    .setTitle("Excel 导入完成")
                    .setMessage("读取行数：" + total +
                            "\n新增待导入：" + inserted +
                            "\n号码异常：" + invalid +
                            "\n重复号码：" + duplicate)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
            new AlertDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage("请确认文件是 .xlsx，且 A 列为姓名、B 列为手机号。\n\n错误信息：" + e.getMessage())
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private void importNextBatch(int count) {
        if (!hasContactPermission()) {
            pendingBatchCount = count;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS}, REQ_CONTACT_PERMISSION);
            return;
        }
        List<Contact> list = db.getNextPending(count);
        if (list.isEmpty()) {
            toast("没有可导入的未导入联系人");
            return;
        }
        confirm("确认导入 " + list.size() + " 人到手机通讯录？", () -> runBatchImport(list));
    }

    private void runBatchImport(List<Contact> list) {
        int success = 0, failed = 0;
        for (Contact c : list) {
            try {
                boolean ok = writeToSystemContacts(c);
                if (ok) {
                    db.updateStatus(c.id, Contact.STATUS_IMPORTED, ContactDbHelper.now());
                    success++;
                } else {
                    db.updateStatus(c.id, Contact.STATUS_FAILED, "");
                    failed++;
                }
            } catch (Exception e) {
                e.printStackTrace();
                db.updateStatus(c.id, Contact.STATUS_FAILED, "");
                failed++;
            }
        }
        refresh();
        new AlertDialog.Builder(this)
                .setTitle("批量导入完成")
                .setMessage("成功：" + success + " 人\n失败：" + failed + " 人")
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    public void onImport(Contact c) {
        if (c == null) return;
        if (c.status != Contact.STATUS_PENDING) {
            toast("当前状态不可导入：" + Contact.statusText(c.status));
            return;
        }
        if (!hasContactPermission()) {
            pendingBatchCount = 1;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS}, REQ_CONTACT_PERMISSION);
            return;
        }
        List<Contact> one = new ArrayList<>();
        one.add(c);
        runBatchImport(one);
    }

    @Override
    public void onEdit(Contact c) {
        showEditDialog(c);
    }

    @Override
    public void onDelete(Contact c) {
        confirm("确认删除该联系人？\n" + c.name + "\n" + c.normalizedPhone, () -> {
            db.delete(c.id);
            refresh();
        });
    }

    private void showEditDialog(Contact old) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etName = new EditText(this);
        etName.setHint("姓名 / 公司名");
        etName.setSingleLine(true);
        EditText etPhone = new EditText(this);
        etPhone.setHint("手机号");
        etPhone.setSingleLine(true);
        EditText etRemark = new EditText(this);
        etRemark.setHint("备注，可不填");
        etRemark.setSingleLine(false);
        etRemark.setMinLines(2);

        if (old != null) {
            etName.setText(old.name);
            etPhone.setText(old.normalizedPhone == null || old.normalizedPhone.isEmpty() ? old.phone : old.normalizedPhone);
            etRemark.setText(old.remark);
        }
        box.addView(etName);
        box.addView(etPhone);
        box.addView(etRemark);

        new AlertDialog.Builder(this)
                .setTitle(old == null ? "新增联系人" : "编辑联系人")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    String normalized = normalizePhone(phone);
                    String remark = etRemark.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("姓名不能为空");
                        return;
                    }

                    Contact c = old == null ? new Contact() : old;
                    c.name = name;
                    c.phone = phone;
                    c.normalizedPhone = normalized;
                    c.remark = remark;
                    if (old == null) c.sourceFile = "手动新增";

                    if (!isValidChinaMobile(normalized)) {
                        c.status = Contact.STATUS_INVALID;
                    } else if (old == null && db.phoneExists(normalized)) {
                        c.status = Contact.STATUS_DUPLICATE;
                    } else if (old == null || old.status == Contact.STATUS_INVALID || old.status == Contact.STATUS_DUPLICATE || old.status == Contact.STATUS_FAILED) {
                        c.status = Contact.STATUS_PENDING;
                    }

                    if (old == null) db.insert(c); else db.update(c);
                    refresh();
                })
                .show();
    }

    private boolean writeToSystemContacts(Contact c) throws Exception {
        if (c == null || c.name == null || c.name.trim().isEmpty() || !isValidChinaMobile(c.normalizedPhone)) return false;
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int rawContactInsertIndex = ops.size();

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, c.name)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, c.normalizedPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());

        if (c.remark != null && !c.remark.trim().isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, c.remark)
                    .build());
        }

        getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        return true;
    }

    private void exportCsv(Uri uri) {
        List<Contact> list = db.getContacts(currentKeyword, currentFilterStatus);
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"))) {
            // 写入 UTF-8 BOM，方便 Excel 直接打开中文不乱码。
            writer.write('\ufeff');
            writer.write("姓名,手机号,状态,来源文件,导入时间,备注\n");
            for (Contact c : list) {
                writer.write(csv(c.name)); writer.write(',');
                writer.write(csv(c.normalizedPhone == null || c.normalizedPhone.isEmpty() ? c.phone : c.normalizedPhone)); writer.write(',');
                writer.write(csv(Contact.statusText(c.status))); writer.write(',');
                writer.write(csv(c.sourceFile)); writer.write(',');
                writer.write(csv(c.importedAt)); writer.write(',');
                writer.write(csv(c.remark)); writer.write('\n');
            }
            toast("导出完成");
        } catch (Exception e) {
            e.printStackTrace();
            toast("导出失败：" + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private boolean hasContactPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACT_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                int count = pendingBatchCount <= 0 ? 1 : pendingBatchCount;
                pendingBatchCount = 0;
                importNextBatch(count);
            } else {
                toast("未获得通讯录写入权限，无法导入手机通讯录");
            }
        }
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("+86")) s = s.substring(3);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
        }
        String d = digits.toString();
        if (d.length() == 13 && d.startsWith("86")) d = d.substring(2);
        if (d.length() > 11 && d.startsWith("0")) {
            // 部分号码可能带前导 0，仅做保守处理：保留末 11 位。
            d = d.substring(d.length() - 11);
        }
        return d;
    }

    private boolean isValidChinaMobile(String phone) {
        return phone != null && phone.matches("1\\d{10}");
    }

    private String getDisplayName(Uri uri) {
        String result = "Excel文件";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void confirm(String message, Runnable yesAction) {
        new AlertDialog.Builder(this)
                .setTitle("确认操作")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> yesAction.run())
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
