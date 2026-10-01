package io.local.phoneuxlogger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "phoneux.db";
    private static final int DB_VERSION = 1;
    private static volatile LogStore instance;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public static LogStore get(Context context) {
        if (instance == null) {
            synchronized (LogStore.class) {
                if (instance == null) instance = new LogStore(context.getApplicationContext());
            }
        }
        return instance;
    }

    private LogStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "kind TEXT NOT NULL," +
                "event_type INTEGER," +
                "event_name TEXT," +
                "package_name TEXT," +
                "class_name TEXT," +
                "view_id TEXT," +
                "window_id INTEGER," +
                "left_px INTEGER, top_px INTEGER, right_px INTEGER, bottom_px INTEGER," +
                "x_px REAL, y_px REAL," +
                "scroll_x INTEGER, scroll_y INTEGER, max_scroll_x INTEGER, max_scroll_y INTEGER," +
                "from_index INTEGER, to_index INTEGER, item_count INTEGER," +
                "action INTEGER," +
                "meta TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)");
        db.execSQL("CREATE INDEX idx_events_pkg ON events(package_name)");
        db.execSQL("CREATE INDEX idx_events_kind ON events(kind)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void insertAsync(ContentValues values) {
        writer.execute(() -> {
            try {
                getWritableDatabase().insert("events", null, values);
            } catch (Throwable ignored) {}
        });
    }

    public long count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public void clear() {
        getWritableDatabase().delete("events", null, null);
    }

    public void exportCsv(OutputStream output) throws IOException {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
             Cursor c = queryAll()) {
            String[] cols = c.getColumnNames();
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) out.write(',');
                writeCsv(out, cols[i]);
            }
            out.newLine();
            while (c.moveToNext()) {
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) out.write(',');
                    if (!c.isNull(i)) writeCsv(out, c.getString(i));
                }
                out.newLine();
            }
        }
    }

    public void exportJson(OutputStream output) throws IOException {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
             Cursor c = queryAll()) {
            out.write("[\n");
            boolean first = true;
            while (c.moveToNext()) {
                if (!first) out.write(",\n");
                first = false;
                out.write("  {");
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (i > 0) out.write(',');
                    String key = c.getColumnName(i);
                    out.write(JSONObject.quote(key));
                    out.write(':');
                    if (c.isNull(i)) {
                        out.write("null");
                    } else {
                        int type = c.getType(i);
                        if (type == Cursor.FIELD_TYPE_INTEGER || type == Cursor.FIELD_TYPE_FLOAT) {
                            out.write(c.getString(i));
                        } else {
                            out.write(JSONObject.quote(c.getString(i)));
                        }
                    }
                }
                out.write('}');
            }
            out.write("\n]\n");
        }
    }

    private Cursor queryAll() {
        return getReadableDatabase().rawQuery("SELECT * FROM events ORDER BY id ASC", null);
    }

    private static void writeCsv(BufferedWriter out, String s) throws IOException {
        out.write('"');
        out.write(s.replace("\"", "\"\""));
        out.write('"');
    }
}
