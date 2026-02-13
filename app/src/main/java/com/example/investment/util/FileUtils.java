package com.example.investment.util;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class FileUtils {
    private static final Object fileLock = new Object();
    
    public static File getFileDataRecord(@NonNull Context context, String fileName) {
        return new File(context.getApplicationContext().getCacheDir(), fileName);
    }
    
    /** @noinspection ResultOfMethodCallIgnored*/
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void saveObject(Context context, String fileName, Object object) throws IOException {
        File file = getFileDataRecord(context, fileName);
        File tempFile = new File(file.getPath() + ".tmp");

        synchronized (fileLock) {
            if (tempFile.exists()) {
                tempFile.delete();
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tempFile))) {
                oos.writeObject(object);
                oos.flush();
            }
            
            Files.copy(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempFile.delete();
        }
    }
    
    public static Object loadObject(Context context, String fileName) throws IOException, ClassNotFoundException {
        File file = getFileDataRecord(context, fileName);
        if (!file.exists()) return null;
        
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return ois.readObject();
        }
    }

}