package com.example.investment.util;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FileUtils {
    private static final Object fileLock = new Object();
    
    public static File getFileDataRecord(@NonNull Context context, String fileName) {
        return new File(context.getApplicationContext().getCacheDir(), fileName);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void saveObject(Context context, String fileName, Object object) throws IOException {
        File file = getFileDataRecord(context, fileName);
        File tempFile = new File(file.getPath() + ".tmp");

        synchronized (fileLock) {
            if (tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tempFile))) {
                oos.writeObject(object);
                oos.flush();
            }
            
            Files.copy(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }
    
    public static Object loadObject(Context context, String fileName) throws IOException, ClassNotFoundException {
        return loadObject(getFileDataRecord(context, fileName));
    }

    public static Object loadObject(File file) throws IOException, ClassNotFoundException {
        if (!file.exists()) return null;

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return ois.readObject();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean copyDataRecord(@NonNull Context context, @NonNull String fileName, @NonNull File destination) throws IOException {
        File file = getFileDataRecord(context, fileName);

        synchronized (fileLock) {
            if (!file.exists()) return false;
            Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    @FunctionalInterface
    public interface OnRecordsReplaced {
        void run() throws IOException;
    }

    // Every data record is replaced or none of them, if the last step fails the previous records are put back
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void replaceDataRecords(
        @NonNull Context context,
        @NonNull Map<String, File> records,
        @NonNull OnRecordsReplaced onRecordsReplaced
    ) throws IOException {
        @NonNull List<String> replacedFileNames = new ArrayList<>();

        synchronized (fileLock) {
            try {
                for (Map.Entry<String, File> record : records.entrySet()) {
                    File file = getFileDataRecord(context, record.getKey());
                    @Nullable File replacement = record.getValue();

                    // A previous file that was left behind must not be put back
                    Files.deleteIfExists(getPreviousFile(file).toPath());
                    if (file.exists()) {
                        Files.copy(file.toPath(), getPreviousFile(file).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (replacement != null) {
                        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    } else {
                        Files.deleteIfExists(file.toPath());
                    }
                    replacedFileNames.add(record.getKey());
                }
                onRecordsReplaced.run();
            } catch (IOException | RuntimeException e) {
                for (String fileName : replacedFileNames) {
                    File file = getFileDataRecord(context, fileName);
                    File previousFile = getPreviousFile(file);
                    try {
                        if (previousFile.exists()) {
                            Files.move(previousFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.deleteIfExists(file.toPath());
                        }
                    } catch (IOException ignored) { }
                }
                throw e;
            } finally {
                for (String fileName : records.keySet()) {
                    //noinspection ResultOfMethodCallIgnored
                    getPreviousFile(getFileDataRecord(context, fileName)).delete();
                }
            }
        }
    }
    private static File getPreviousFile(@NonNull File file) {
        return new File(file.getPath() + ".previous");
    }
}