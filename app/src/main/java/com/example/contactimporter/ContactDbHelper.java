package com.example.contactimporter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ContactDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "contacts_importer.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "contacts";

    public ContactDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "phone TEXT," +
                "normalized_phone TEXT," +
                "status INTEGER NOT NULL," +
                "remark TEXT," +
                "source_file TEXT," +
                "imported_at TEXT," +
                "created_at TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_contacts_status ON " + TABLE + "(status)");
        db.execSQL("CREATE INDEX idx_contacts_phone ON " + TABLE + "(normalized_phone)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long insert(Contact c) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toValues(c, true);
        return db.insert(TABLE, null, cv);
    }

    public void update(Contact c) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toValues(c, false);
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(c.id)});
    }

    public void updateStatus(long id, int status, String importedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        cv.put("imported_at", importedAt);
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    public void resetImportedToPending() {
        ContentValues cv = new ContentValues();
        cv.put("status", Contact.STATUS_PENDING);
        cv.putNull("imported_at");
        getWritableDatabase().update(TABLE, cv, "status=? OR status=?", new String[]{
                String.valueOf(Contact.STATUS_IMPORTED), String.valueOf(Contact.STATUS_FAILED)
        });
    }

    public boolean phoneExists(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.trim().isEmpty()) return false;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE normalized_phone=? AND status<>?", new String[]{
                normalizedPhone, String.valueOf(Contact.STATUS_DUPLICATE)
        })) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    public List<Contact> getContacts(String keyword, int filterStatus) {
        List<Contact> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();

        if (filterStatus >= 0) {
            where.append(" AND status=?");
            args.add(String.valueOf(filterStatus));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            where.append(" AND (name LIKE ? OR phone LIKE ? OR normalized_phone LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }

        String sql = "SELECT * FROM " + TABLE + " WHERE " + where + " ORDER BY id ASC";
        try (Cursor c = db.rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        }
        return list;
    }

    public List<Contact> getNextPending(int limit) {
        List<Contact> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE status=? ORDER BY id ASC LIMIT ?", new String[]{
                String.valueOf(Contact.STATUS_PENDING), String.valueOf(limit)
        })) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public Stats getStats() {
        Stats s = new Stats();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT status, COUNT(*) FROM " + TABLE + " GROUP BY status", null)) {
            while (c.moveToNext()) {
                int status = c.getInt(0);
                int count = c.getInt(1);
                s.total += count;
                if (status == Contact.STATUS_PENDING) s.pending = count;
                else if (status == Contact.STATUS_IMPORTED) s.imported = count;
                else if (status == Contact.STATUS_FAILED) s.failed = count;
                else if (status == Contact.STATUS_INVALID) s.invalid = count;
                else if (status == Contact.STATUS_DUPLICATE) s.duplicate = count;
            }
        }
        return s;
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private ContentValues toValues(Contact c, boolean includeCreatedAt) {
        ContentValues cv = new ContentValues();
        cv.put("name", c.name == null ? "" : c.name);
        cv.put("phone", c.phone == null ? "" : c.phone);
        cv.put("normalized_phone", c.normalizedPhone == null ? "" : c.normalizedPhone);
        cv.put("status", c.status);
        cv.put("remark", c.remark == null ? "" : c.remark);
        cv.put("source_file", c.sourceFile == null ? "" : c.sourceFile);
        cv.put("imported_at", c.importedAt == null ? "" : c.importedAt);
        if (includeCreatedAt) cv.put("created_at", now());
        return cv;
    }

    private Contact fromCursor(Cursor c) {
        Contact x = new Contact();
        x.id = c.getLong(c.getColumnIndexOrThrow("id"));
        x.name = c.getString(c.getColumnIndexOrThrow("name"));
        x.phone = c.getString(c.getColumnIndexOrThrow("phone"));
        x.normalizedPhone = c.getString(c.getColumnIndexOrThrow("normalized_phone"));
        x.status = c.getInt(c.getColumnIndexOrThrow("status"));
        x.remark = c.getString(c.getColumnIndexOrThrow("remark"));
        x.sourceFile = c.getString(c.getColumnIndexOrThrow("source_file"));
        x.importedAt = c.getString(c.getColumnIndexOrThrow("imported_at"));
        x.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
        return x;
    }

    public static class Stats {
        public int total;
        public int pending;
        public int imported;
        public int failed;
        public int invalid;
        public int duplicate;
    }
}
