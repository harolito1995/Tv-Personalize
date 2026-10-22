package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UsbFolderActivity extends Activity {

    static final String EXTRA_DIR = "dir";
    static final String EXTRA_ERROR = "error";

    private File root;
    private File current;
    private ListView list;
    private TextView path;
    private TextView status;
    private final List<Row> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_folder);

        path = findViewById(R.id.path);
        status = findViewById(R.id.status);
        list = findViewById(R.id.list);

        List<File> volumes = volumes();
        if (volumes.isEmpty()) {
            finishWithError(R.string.usb_folder_no_volume);
            return;
        }

        root = volumes.get(0);
        current = initialDir(root);
        adapter = new ArrayAdapter<>(this, R.layout.usb_folder_row, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRow(position));
        show(current);
        list.post(list::requestFocus);
    }

    private void onRow(int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        if (row.use) {
            choose(current);
        } else if (row.up) {
            show(current.getParentFile());
        } else {
            show(row.dir);
        }
    }

    private void show(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        if (!isInside(dir, root)) return;

        current = dir;
        rows.clear();
        List<String> labels = new ArrayList<>();
        if (!root.equals(dir)) {
            rows.add(Row.up());
            labels.add("\u2191  " + getString(R.string.usb_folder_up));
        }

        File[] kids = dir.listFiles();
        List<File> folders = new ArrayList<>();
        if (kids != null) {
            for (File kid : kids) {
                String name = kid.getName();
                if (!kid.isDirectory() || name.startsWith(".")) continue;
                if (name.equals("Android") || name.equals("LOST.DIR")
                        || name.equals("System Volume Information")) continue;
                folders.add(kid);
            }
        }
        folders.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File folder : folders) {
            rows.add(Row.folder(folder));
            labels.add(folder.getName() + "/");
        }

        boolean writable = dir.canWrite();
        if (writable) {
            rows.add(Row.use());
            labels.add("\u2713  " + getString(R.string.usb_folder_use));
        }

        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();

        path.setText(dir.getAbsolutePath());
        status.setText(writable ? R.string.usb_folder_hint : R.string.usb_folder_read_only);
        list.setSelection(0);
        list.requestFocus();
    }

    private void choose(File dir) {
        Intent out = new Intent();
        out.putExtra(EXTRA_DIR, dir.getAbsolutePath());
        setResult(RESULT_OK, out);
        finish();
    }

    private void finishWithError(int message) {
        Intent out = new Intent();
        out.putExtra(EXTRA_ERROR, getString(message));
        setResult(RESULT_CANCELED, out);
        finish();
    }

    private static boolean isInside(File dir, File root) {
        if (root == null) return false;
        String parent = root.getAbsolutePath();
        String child = dir.getAbsolutePath();
        return child.equals(parent) || child.startsWith(parent + File.separator);
    }

    private static File initialDir(File volume) {
        for (String name : new String[]{"Wallpaper", "Pictures", "Download"}) {
            File dir = new File(volume, name);
            if (dir.isDirectory() && dir.canRead()) return dir;
        }
        return volume;
    }

    private List<File> volumes() {
        List<File> out = new ArrayList<>();
        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume volume : sm.getStorageVolumes()) {
                if (!volume.isRemovable()
                        || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File dir = volumeDir(volume);
                if (dir != null && dir.isDirectory() && dir.canRead() && !out.contains(dir)) {
                    out.add(dir);
                }
            }
        }
        if (out.isEmpty()) {
            File[] mounted = new File("/storage").listFiles();
            if (mounted != null) {
                for (File dir : mounted) {
                    String name = dir.getName();
                    if (name.equals("emulated") || name.equals("self")
                            || name.equals("enc_emulated")) continue;
                    if (dir.isDirectory() && dir.canRead() && !out.contains(dir)) out.add(dir);
                }
            }
        }
        Collections.sort(out, (a, b) -> {
            int write = Boolean.compare(b.canWrite(), a.canWrite());
            return write != 0 ? write : a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
        });
        return out;
    }

    private static File volumeDir(StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.getDirectory();
        String uuid = volume.getUuid();
        return uuid == null ? null : new File("/storage", uuid);
    }

    private static final class Row {
        final File dir;
        final boolean up;
        final boolean use;

        private Row(File dir, boolean up, boolean use) {
            this.dir = dir;
            this.up = up;
            this.use = use;
        }

        static Row folder(File dir) {
            return new Row(dir, false, false);
        }

        static Row up() {
            return new Row(null, true, false);
        }

        static Row use() {
            return new Row(null, false, true);
        }
    }
}
