package com.example.investment.util;

import static com.example.investment.util.BigDecimalUtils.getSafeBigDecimalPrefs;
import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.BACKUP_DATA_RECORDS;
import static com.example.investment.util.Constants.BACKUP_ENTRY_PREFERENCES;
import static com.example.investment.util.Constants.BACKUP_FILE_NAME;
import static com.example.investment.util.Constants.BACKUP_TEMP_FILE_NAME;
import static com.example.investment.util.Constants.BACKUP_VERSION;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.DEFAULT_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.FILE_RECORD_PROXIES;
import static com.example.investment.util.Constants.FILE_RECORD_SCREENER_CRYPTO;
import static com.example.investment.util.Constants.FILE_RECORD_SCREENER_STOCKS;
import static com.example.investment.util.Constants.FILE_RECORD_TICKERS;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.PREF_ASSET_TYPE;
import static com.example.investment.util.Constants.PREF_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.PREF_EXPORT_FOLDER_URI;
import static com.example.investment.util.Constants.PREF_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREF_LIST_VIEW_ASSET_TYPE;
import static com.example.investment.util.Constants.PREF_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREF_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ProxyDataRecord;
import com.example.investment.model.ProxyStateRecord;
import com.example.investment.model.ScreenerRecord;
import com.example.investment.model.ScreenerSymbol;
import com.example.investment.model.TickerDataRecord;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.repository.YahooFinanceRepository;
import com.example.investment.task.TickerDataLoader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


public class BackupUtils {
    private static final Object backupLock = new Object();
    private static final String ZIP_MIME_TYPE = "application/zip";
    private static final String GENERIC_MIME_TYPE = "application/octet-stream";
    private static final String EXPORT_DIRECTORY_NAME = "export";
    private static final String IMPORT_DIRECTORY_NAME = "import";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PREFERENCES_SIZE_BYTES = 64 * 1024;

    private record BackupPreferences(
        @NonNull String listViewAssetType,
        @NonNull String investmentType,
        @NonNull String assetType,
        int numberOfTopHoldings,
        @NonNull BigDecimal marketCapThreshold,
        @NonNull BigDecimal dailyDollarVolumeThreshold,
        @NonNull BigDecimal ageInSecondsThreshold
    ) {}

    // Export Folder
    public static @Nullable Uri getExportFolderUri(@NonNull Context context) {
        @NonNull SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        @Nullable String folderUriString = sharedPreferences.getString(PREF_EXPORT_FOLDER_URI, null);
        return folderUriString == null || folderUriString.isEmpty() ? null : Uri.parse(folderUriString);
    }

    public static void saveExportFolder(@NonNull Context context, @NonNull Uri folderUri) {
        @NonNull ContentResolver resolver = context.getContentResolver();
        @Nullable Uri oldFolderUri = getExportFolderUri(context);

        resolver.takePersistableUriPermission(
            folderUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_EXPORT_FOLDER_URI, folderUri.toString())
        .apply();

        // Persisted permissions are limited, the replaced folder shouldn't keep one
        if (oldFolderUri != null && !oldFolderUri.equals(folderUri)) {
            try {
                resolver.releasePersistableUriPermission(
                    oldFolderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception e) {
                Logger.error("Error releasing the old export folder permission", e);
            }
        }
    }

    // Folder is chosen, the permission is still kept, and the folder still exists
    public static boolean isExportFolderAccessible(@NonNull Context context) {
        @Nullable Uri folderUri = getExportFolderUri(context);
        if (folderUri == null) return false;

        @NonNull ContentResolver resolver = context.getContentResolver();
        boolean hasPermission = resolver.getPersistedUriPermissions().stream().anyMatch(permission ->
            permission.getUri().equals(folderUri) && permission.isReadPermission() && permission.isWritePermission()
        );
        if (!hasPermission) return false;

        return isFolderWritable(context, folderUri);
    }

    public static boolean isFolderWritable(@NonNull Context context, @NonNull Uri folderUri) {
        try (Cursor cursor = context.getContentResolver().query(
            getFolderDocumentUri(folderUri),
            new String[]{DocumentsContract.Document.COLUMN_FLAGS},
            null,
            null,
            null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            return (cursor.getInt(0) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static @NonNull String getFolderDisplayName(@NonNull Uri folderUri) {
        try {
            @NonNull String documentId = DocumentsContract.getTreeDocumentId(folderUri);
            int separatorIndex = documentId.indexOf(':');
            if (separatorIndex < 0) return documentId;

            @NonNull String volume = documentId.substring(0, separatorIndex);
            @NonNull String path = documentId.substring(separatorIndex + 1);
            @NonNull String volumeName = "primary".equals(volume) ? "Internal storage" : volume;
            return path.isEmpty() ? volumeName : volumeName + "/" + path;
        } catch (Exception e) {
            return folderUri.toString();
        }
    }

    public static @NonNull String getFileDisplayName(@NonNull Context context, @NonNull Uri fileUri) {
        try (Cursor cursor = context.getContentResolver().query(
            fileUri,
            new String[]{OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { }
        @Nullable String lastPathSegment = fileUri.getLastPathSegment();
        return lastPathSegment != null ? lastPathSegment : BACKUP_FILE_NAME;
    }

    // Export
    public static boolean hasExportableData(@NonNull Context context) {
        return FileUtils.getFileDataRecord(context, FILE_RECORD_TICKERS).exists();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void autoExport(@NonNull Context context) {
        @Nullable Uri folderUri = getExportFolderUri(context);
        if (folderUri == null) return;

        try {
            exportToFolder(context, folderUri);
        } catch (Exception e) {
            Logger.error("Error auto exporting the backup", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void exportToFolder(@NonNull Context context, @NonNull Uri folderUri) throws IOException {
        synchronized (backupLock) {
            @NonNull ContentResolver resolver = context.getContentResolver();
            @NonNull File localZip = new File(context.getNoBackupFilesDir(), BACKUP_FILE_NAME + ".local");

            try {
                writeBackupZip(context, localZip);
                verifyBackupZip(localZip);
                @NonNull byte[] expectedDigest;
                try (InputStream stream = new FileInputStream(localZip)) {
                    expectedDigest = getDigest(stream);
                }

                @NonNull Uri folderDocumentUri = getFolderDocumentUri(folderUri);
                @Nullable Uri oldTempUri = findChild(resolver, folderUri, BACKUP_TEMP_FILE_NAME);
                if (oldTempUri != null) DocumentsContract.deleteDocument(resolver, oldTempUri);

                @NonNull Uri tempUri = writeVerifiedDocument(resolver, folderDocumentUri, GENERIC_MIME_TYPE, BACKUP_TEMP_FILE_NAME, localZip, expectedDigest);

                @Nullable Uri oldBackupUri = findChild(resolver, folderUri, BACKUP_FILE_NAME);
                if (oldBackupUri != null) DocumentsContract.deleteDocument(resolver, oldBackupUri);
                try {
                    if (DocumentsContract.renameDocument(resolver, tempUri, BACKUP_FILE_NAME) == null) {
                        throw new IOException("Can't rename the temporary backup file");
                    }
                } catch (Exception e) {
                    Logger.error("Error renaming the temporary backup file", e);
                    writeVerifiedDocument(resolver, folderDocumentUri, ZIP_MIME_TYPE, BACKUP_FILE_NAME, localZip, expectedDigest);
                    deleteQuietly(resolver, tempUri);
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                localZip.delete();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void writeBackupZip(@NonNull Context context, @NonNull File zipFile) throws IOException {
        @NonNull File snapshotDirectory = new File(context.getNoBackupFilesDir(), EXPORT_DIRECTORY_NAME);
        deleteRecursively(snapshotDirectory);
        //noinspection ResultOfMethodCallIgnored
        snapshotDirectory.mkdirs();

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            for (String fileName : BACKUP_DATA_RECORDS) {
                // A copy is zipped, so a running update can't change the data record halfway
                @NonNull File snapshot = new File(snapshotDirectory, fileName);
                if (!FileUtils.copyDataRecord(context, fileName, snapshot)) {
                    if (FILE_RECORD_TICKERS.equals(fileName)) throw new IOException("There are no tickers to export yet");
                    continue;
                }

                zip.putNextEntry(new ZipEntry(fileName));
                try (InputStream stream = new FileInputStream(snapshot)) {
                    copy(stream, zip);
                }
                zip.closeEntry();
            }

            zip.putNextEntry(new ZipEntry(BACKUP_ENTRY_PREFERENCES));
            zip.write(getPreferencesJson(context).toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (JSONException e) {
            throw new IOException("Error writing the configurations", e);
        } finally {
            deleteRecursively(snapshotDirectory);
        }
    }

    private static @NonNull JSONObject getPreferencesJson(@NonNull Context context) throws JSONException {
        @NonNull SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        @NonNull JSONObject json = new JSONObject();
        json.put("version", BACKUP_VERSION);
        json.put("exportedAt", System.currentTimeMillis());
        json.put(PREF_LIST_VIEW_ASSET_TYPE, sharedPreferences.getString(PREF_LIST_VIEW_ASSET_TYPE, ALL_TYPE));
        json.put(PREF_INVESTMENT_TYPE, sharedPreferences.getString(PREF_INVESTMENT_TYPE, PASSIVE_INVESTMENT_TYPE));
        json.put(PREF_ASSET_TYPE, sharedPreferences.getString(PREF_ASSET_TYPE, ALL_TYPE));
        json.put(PREF_NUMBER_OF_TOP_HOLDINGS, sharedPreferences.getInt(PREF_NUMBER_OF_TOP_HOLDINGS, DEFAULT_NUMBER_OF_TOP_HOLDINGS));
        json.put(PREF_MARKET_CAP_THRESHOLD, getSafeBigDecimalPrefs(sharedPreferences, PREF_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD).toPlainString());
        json.put(PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, getSafeBigDecimalPrefs(sharedPreferences, PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD).toPlainString());
        json.put(PREF_AGE_IN_SECONDS_THRESHOLD, getSafeBigDecimalPrefs(sharedPreferences, PREF_AGE_IN_SECONDS_THRESHOLD, DEFAULT_AGE_IN_SECONDS_THRESHOLD).toPlainString());
        return json;
    }

    private static @NonNull Uri writeVerifiedDocument(
        @NonNull ContentResolver resolver,
        @NonNull Uri folderDocumentUri,
        @NonNull String mimeType,
        @NonNull String displayName,
        @NonNull File source,
        @NonNull byte[] expectedDigest
    ) throws IOException {
        @Nullable Uri documentUri = DocumentsContract.createDocument(resolver, folderDocumentUri, mimeType, displayName);
        if (documentUri == null) throw new IOException("Can't create the backup file in the folder");

        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(documentUri, "w")) {
                if (output == null) throw new IOException("Can't write the backup file");
                copy(input, output);
                output.flush();
            }

            try (InputStream input = resolver.openInputStream(documentUri)) {
                if (input == null) throw new IOException("Can't read the saved backup file");
                if (!MessageDigest.isEqual(getDigest(input), expectedDigest)) {
                    throw new IOException("The saved backup file doesn't match the exported data");
                }
            }
            return documentUri;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(resolver, documentUri);
            throw e;
        }
    }

    // Import
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void importFromFile(@NonNull Context context, @NonNull Uri fileUri, @NonNull Object importToken) throws IOException {
        synchronized (backupLock) {
            ensureImportActive(importToken);
            @NonNull File stagingDirectory = new File(context.getNoBackupFilesDir(), IMPORT_DIRECTORY_NAME);
            deleteRecursively(stagingDirectory);
            //noinspection ResultOfMethodCallIgnored
            stagingDirectory.mkdirs();

            try {
                @NonNull File zipFile = new File(stagingDirectory, "backup.zip");
                copyUriToFile(context.getContentResolver(), fileUri, zipFile);
                ensureImportActive(importToken);
                verifyBackupZip(zipFile);
                ensureImportActive(importToken);

                @NonNull Map<String, File> records = new HashMap<>();
                @NonNull BackupPreferences preferences;
                try (ZipFile zip = new ZipFile(zipFile)) {
                    for (String fileName : BACKUP_DATA_RECORDS) {
                        ensureImportActive(importToken);
                        @Nullable ZipEntry entry = zip.getEntry(fileName);
                        if (entry == null) {
                            records.put(fileName, null);
                            continue;
                        }
                        @NonNull File stagedFile = new File(stagingDirectory, fileName);
                        try (InputStream stream = zip.getInputStream(entry)) {
                            copyToFile(stream, stagedFile);
                        }
                        records.put(fileName, stagedFile);
                    }

                    @NonNull ZipEntry preferencesEntry = requireEntry(zip, BACKUP_ENTRY_PREFERENCES);
                    try (InputStream stream = zip.getInputStream(preferencesEntry)) {
                        preferences = parsePreferences(readBytes(stream));
                    }
                }
                validateRecords(records);
                ensureImportActive(importToken);

                if (!isNewerProxyRecord(context, records.get(FILE_RECORD_PROXIES))) records.remove(FILE_RECORD_PROXIES);

                boolean wasInterrupted = Thread.interrupted();
                try {
                    FileUtils.replaceDataRecords(context, records, () -> applyPreferences(context, preferences));
                    TickerDataLoader.getInstance().invalidateCache();
                    TickerDataLoader.getInstance().clearRefreshPoint(context);
                    if (records.containsKey(FILE_RECORD_PROXIES)) YahooFinanceRepository.invalidateProxyCache();
                } finally {
                    if (wasInterrupted) Thread.currentThread().interrupt();
                }
            } finally {
                deleteRecursively(stagingDirectory);
            }
        }
    }

    private static void ensureImportActive(@NonNull Object importToken) {
        if (Thread.currentThread().isInterrupted() || ImportUpdateGuard.isNotCurrentImport(importToken)) {
            throw new CancellationException("Import is canceled by a newer import");
        }
    }

    private static void validateRecords(@NonNull Map<String, File> records) throws IOException {
        @Nullable File tickersFile = records.get(FILE_RECORD_TICKERS);
        if (tickersFile == null) throw new IOException("Backup has no tickers");

        if (!(loadStagedRecord(tickersFile) instanceof TickerDataRecord record)
            || record.tickers() == null
            || record.tickers().isEmpty()
            || hasNonValidTicker(record.tickers())
        ) {
            throw new IOException("Backup tickers are empty or invalid");
        }

        for (Map.Entry<String, File> entry : records.entrySet()) {
            @Nullable File file = entry.getValue();
            if (file == null || FILE_RECORD_TICKERS.equals(entry.getKey())) continue;

            if (FILE_RECORD_PROXIES.equals(entry.getKey())) {
                if (!(loadStagedRecord(file) instanceof ProxyDataRecord proxyRecord)
                    || proxyRecord.proxies() == null
                    || !hasOnlyProxies(proxyRecord.proxies())
                ) {
                    throw new IOException("Backup proxies are invalid");
                }
            } else if (
                FILE_RECORD_SCREENER_CRYPTO.equals(entry.getKey())
                || FILE_RECORD_SCREENER_STOCKS.equals(entry.getKey())
            ) {
                @NonNull String expectedScreenerId = FILE_RECORD_SCREENER_CRYPTO.equals(entry.getKey())
                    ? SCREENER_CRYPTO : SCREENER_STOCKS;
                @NonNull String expectedAssetType = FILE_RECORD_SCREENER_CRYPTO.equals(entry.getKey())
                    ? CRYPTOCURRENCY_TYPE : STOCK_TYPE;
                if (!(loadStagedRecord(file) instanceof ScreenerRecord screenerRecord)
                    || !expectedScreenerId.equals(screenerRecord.screenerId())
                    || screenerRecord.symbols().isEmpty()
                    || hasInvalidScreenerSymbols(screenerRecord.symbols(), expectedAssetType)
                ) {
                    throw new IOException("Backup screener records are invalid");
                }
            } else if (!(loadStagedRecord(file) instanceof List<?> leadingTickers) || hasNonValidTicker(leadingTickers)) {
                throw new IOException("Backup leading tickers are invalid");
            }
        }
    }
    private static boolean hasNonValidTicker(@NonNull List<?> tickers) {
        return !tickers.stream().allMatch(ticker -> ticker instanceof TickerData);
    }
    private static boolean hasOnlyProxies(@NonNull List<?> proxies) {
        return proxies.stream().allMatch(proxy -> proxy instanceof ProxyStateRecord stateRecord && stateRecord.address() != null);
    }
    private static boolean hasInvalidScreenerSymbols(@NonNull List<ScreenerSymbol> symbols, @NonNull String expectedAssetType) {
        for (ScreenerSymbol symbol : symbols) {
            if (symbol == null
                || symbol.symbol().isBlank()
                || !expectedAssetType.equals(symbol.type())
                || symbol.marketCap().compareTo(BigDecimal.ZERO) < 0
            ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNewerProxyRecord(@NonNull Context context, @Nullable File stagedFile) throws IOException {
        if (stagedFile == null) return false;
        if (!(loadStagedRecord(stagedFile) instanceof ProxyDataRecord importedRecord)) return false;

        try {
            if (!(FileUtils.loadObject(context, FILE_RECORD_PROXIES) instanceof ProxyDataRecord currentRecord)) return true;
            return importedRecord.timestamp() > currentRecord.timestamp();
        } catch (ClassNotFoundException | IOException | RuntimeException e) {
            Logger.error("Error loading the current proxies", e);
            return true;
        }
    }
    private static @Nullable Object loadStagedRecord(@NonNull File file) throws IOException {
        try {
            return FileUtils.loadObject(file);
        } catch (ClassNotFoundException | IOException | RuntimeException e) {
            throw new IOException("Backup is not compatible with this version of the app", e);
        }
    }

    private static @NonNull BackupPreferences parsePreferences(@NonNull byte[] bytes) throws IOException {
        try {
            @NonNull JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (json.getInt("version") > BACKUP_VERSION) {
                throw new IOException("Backup was made by a newer version of the app");
            }

            @NonNull String listViewAssetType = json.getString(PREF_LIST_VIEW_ASSET_TYPE);
            @NonNull String investmentType = json.getString(PREF_INVESTMENT_TYPE);
            @NonNull String assetType = json.getString(PREF_ASSET_TYPE);
            int numberOfTopHoldings = json.getInt(PREF_NUMBER_OF_TOP_HOLDINGS);
            @NonNull BigDecimal marketCapThreshold = new BigDecimal(json.getString(PREF_MARKET_CAP_THRESHOLD));
            @NonNull BigDecimal dailyDollarVolumeThreshold = new BigDecimal(json.getString(PREF_DAILY_DOLLAR_VOLUME_THRESHOLD));
            @NonNull BigDecimal ageInSecondsThreshold = new BigDecimal(json.getString(PREF_AGE_IN_SECONDS_THRESHOLD));

            if (isNotAnAssetType(listViewAssetType)
                || isNotAnAssetType(assetType)
                || !(PASSIVE_INVESTMENT_TYPE.equals(investmentType) || MOMENTUM_INVESTMENT_TYPE.equals(investmentType))
                || numberOfTopHoldings < 1
                || marketCapThreshold.compareTo(BigDecimal.ZERO) <= 0
                || dailyDollarVolumeThreshold.compareTo(BigDecimal.ZERO) <= 0
                || ageInSecondsThreshold.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IOException("Backup configurations are invalid");
            }

            return new BackupPreferences(
                listViewAssetType,
                investmentType,
                assetType,
                numberOfTopHoldings,
                marketCapThreshold,
                dailyDollarVolumeThreshold,
                ageInSecondsThreshold
            );
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("Backup configurations are invalid", e);
        }
    }
    private static boolean isNotAnAssetType(@NonNull String assetType) {
        return !ALL_TYPE.equals(assetType) && !CRYPTOCURRENCY_TYPE.equals(assetType) && !STOCK_TYPE.equals(assetType);
    }

    private static void applyPreferences(@NonNull Context context, @NonNull BackupPreferences preferences) throws IOException {
        boolean isSaved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LIST_VIEW_ASSET_TYPE, preferences.listViewAssetType())
            .putString(PREF_INVESTMENT_TYPE, preferences.investmentType())
            .putString(PREF_ASSET_TYPE, preferences.assetType())
            .putInt(PREF_NUMBER_OF_TOP_HOLDINGS, preferences.numberOfTopHoldings())
            .putString(PREF_MARKET_CAP_THRESHOLD, preferences.marketCapThreshold().stripTrailingZeros().toPlainString())
            .putString(PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, preferences.dailyDollarVolumeThreshold().stripTrailingZeros().toPlainString())
            .putString(PREF_AGE_IN_SECONDS_THRESHOLD, preferences.ageInSecondsThreshold().stripTrailingZeros().toPlainString())
            .commit();
        if (!isSaved) throw new IOException("Error saving the configurations");
    }

    // Zip - checks the whole zip if entries can be read, and each of them matches its saved size and checksum
    private static void verifyBackupZip(@NonNull File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            @NonNull byte[] buffer = new byte[BUFFER_SIZE];
            @NonNull Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                @NonNull ZipEntry entry = entries.nextElement();
                @NonNull CRC32 checksum = new CRC32();
                long size = 0;

                try (InputStream stream = zip.getInputStream(entry)) {
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        checksum.update(buffer, 0, read);
                        size += read;
                    }
                }
                if (checksum.getValue() != entry.getCrc() || size != entry.getSize()) {
                    throw new IOException("Backup is corrupted");
                }
            }

            requireEntry(zip, FILE_RECORD_TICKERS);
            requireEntry(zip, BACKUP_ENTRY_PREFERENCES);
        } catch (ZipException e) {
            // Not a zip, or the zip is incomplete
            throw new IOException("Backup is not a valid file", e);
        }
    }
    private static @NonNull ZipEntry requireEntry(@NonNull ZipFile zip, @NonNull String name) throws IOException {
        @Nullable ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Backup is incomplete");
        return entry;
    }

    // Streams and Files
    private static @NonNull Uri getFolderDocumentUri(@NonNull Uri folderUri) {
        return DocumentsContract.buildDocumentUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri));
    }

    private static @Nullable Uri findChild(@NonNull ContentResolver resolver, @NonNull Uri folderUri, @NonNull String displayName) {
        @NonNull Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri));

        try (Cursor cursor = resolver.query(
            childrenUri,
            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                if (displayName.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(folderUri, cursor.getString(0));
                }
            }
        }
        return null;
    }

    private static void deleteQuietly(@NonNull ContentResolver resolver, @NonNull Uri documentUri) {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri);
        } catch (Exception ignored) { }
    }

    private static void copyUriToFile(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File destination) throws IOException {
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Can't open the backup file");
            copyToFile(stream, destination);
        }
    }

    private static void copyToFile(@NonNull InputStream input, @NonNull File destination) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            @NonNull byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                SafeSingleTaskRunner.ensureActive();
                total += read;
                if (total > Constants.BACKUP_MAX_SIZE_BYTES) {
                    throw new IOException(String.format(Locale.ROOT, "Backup is larger than %d MB", Constants.BACKUP_MAX_SIZE_BYTES / (1024 * 1024)));
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static @NonNull byte[] readBytes(@NonNull InputStream input) throws IOException {
        @NonNull ByteArrayOutputStream output = new ByteArrayOutputStream();
        @NonNull byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > BackupUtils.MAX_PREFERENCES_SIZE_BYTES) throw new IOException("Backup configurations are too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        @NonNull byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static @NonNull byte[] getDigest(@NonNull InputStream input) throws IOException {
        try {
            @NonNull MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream stream = new DigestInputStream(new BufferedInputStream(input), digest)) {
                @NonNull byte[] buffer = new byte[BUFFER_SIZE];
                //noinspection StatementWithEmptyBody
                while (stream.read(buffer) != -1) { }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Can't check the backup file", e);
        }
    }

    private static void deleteRecursively(@NonNull File file) {
        @Nullable File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}