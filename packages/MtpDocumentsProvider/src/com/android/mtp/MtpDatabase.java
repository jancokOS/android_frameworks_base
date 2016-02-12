/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mtp;

import static com.android.mtp.MtpDatabaseConstants.*;

import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.util.Objects;

/**
 * Database for MTP objects.
 * The object handle which is identifier for object in MTP protocol is not stable over sessions.
 * When we resume the process, we need to remap our document ID with MTP's object handle.
 *
 * If the remote MTP device is backed by typical file system, the file name
 * is unique among files in a directory. However, MTP protocol itself does
 * not guarantee the uniqueness of name so we cannot use fullpath as ID.
 *
 * Instead of fullpath, we use artificial ID generated by MtpDatabase itself. The database object
 * remembers the map of document ID and object handle, and remaps new object handle with document ID
 * by comparing the directory structure and object name.
 *
 * To start putting documents into the database, the client needs to call
 * {@link Mapper#startAddingDocuments(String)} with the parent document ID. Also it
 * needs to call {@link Mapper#stopAddingDocuments(String)} after putting all child
 * documents to the database. (All explanations are same for root documents)
 *
 * database.getMapper().startAddingDocuments();
 * database.getMapper().putChildDocuments();
 * database.getMapper().stopAddingDocuments();
 *
 * To update the existing documents, the client code can repeat to call the three methods again.
 * The newly added rows update corresponding existing rows that have same MTP identifier like
 * objectHandle.
 *
 * The client can call putChildDocuments multiple times to add documents by chunk, but it needs to
 * put all documents under the parent before calling stopAddingChildDocuments. Otherwise missing
 * documents are regarded as deleted, and will be removed from the database.
 *
 * If the client calls clearMtpIdentifier(), it clears MTP identifier in the database. In this case,
 * the database tries to find corresponding rows by using document's name instead of MTP identifier
 * at the next update cycle.
 *
 * TODO: Improve performance by SQL optimization.
 */
class MtpDatabase {
    private final SQLiteDatabase mDatabase;
    private final Mapper mMapper;

    SQLiteDatabase getSQLiteDatabase() {
        return mDatabase;
    }

    MtpDatabase(Context context, int flags) {
        final OpenHelper helper = new OpenHelper(context, flags);
        mDatabase = helper.getWritableDatabase();
        mMapper = new Mapper(this);
    }

    void close() {
        mDatabase.close();
    }

    /**
     * Returns operations for mapping.
     * @return Mapping operations.
     */
    Mapper getMapper() {
        return mMapper;
    }

    /**
     * Queries roots information.
     * @param columnNames Column names defined in {@link android.provider.DocumentsContract.Root}.
     * @return Database cursor.
     */
    Cursor queryRoots(Resources resources, String[] columnNames) {
        final String selection =
                COLUMN_ROW_STATE + " IN (?, ?) AND " + COLUMN_DOCUMENT_TYPE + " = ?";
        final Cursor deviceCursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(COLUMN_DEVICE_ID),
                selection,
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED, DOCUMENT_TYPE_DEVICE),
                COLUMN_DEVICE_ID,
                null,
                null,
                null);

        try {
            final SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
            builder.setTables(JOIN_ROOTS);
            builder.setProjectionMap(COLUMN_MAP_ROOTS);
            final MatrixCursor result = new MatrixCursor(columnNames);
            final ContentValues values = new ContentValues();

            while (deviceCursor.moveToNext()) {
                final int deviceId = deviceCursor.getInt(0);
                final Cursor storageCursor = builder.query(
                        mDatabase,
                        columnNames,
                        selection + " AND " + COLUMN_DEVICE_ID + " = ?",
                        strings(ROW_STATE_VALID,
                                ROW_STATE_INVALIDATED,
                                DOCUMENT_TYPE_STORAGE,
                                deviceId),
                        null,
                        null,
                        null);
                try {
                    values.clear();
                    try (final Cursor deviceRoot = builder.query(
                            mDatabase,
                            columnNames,
                            selection + " AND " + COLUMN_DEVICE_ID + " = ?",
                            strings(ROW_STATE_VALID,
                                    ROW_STATE_INVALIDATED,
                                    DOCUMENT_TYPE_DEVICE,
                                    deviceId),
                            null,
                            null,
                            null)) {
                        deviceRoot.moveToNext();
                        DatabaseUtils.cursorRowToContentValues(deviceRoot, values);
                    }

                    if (storageCursor.getCount() != 0) {
                        long capacityBytes = 0;
                        long availableBytes = 0;
                        final int capacityIndex =
                                storageCursor.getColumnIndex(Root.COLUMN_CAPACITY_BYTES);
                        final int availableIndex =
                                storageCursor.getColumnIndex(Root.COLUMN_AVAILABLE_BYTES);
                        while (storageCursor.moveToNext()) {
                            // If requested columnNames does not include COLUMN_XXX_BYTES, we
                            // don't calculate corresponding values.
                            if (capacityIndex != -1) {
                                capacityBytes += storageCursor.getLong(capacityIndex);
                            }
                            if (availableIndex != -1) {
                                availableBytes += storageCursor.getLong(availableIndex);
                            }
                        }
                        values.put(Root.COLUMN_CAPACITY_BYTES, capacityBytes);
                        values.put(Root.COLUMN_AVAILABLE_BYTES, availableBytes);
                    } else {
                        values.putNull(Root.COLUMN_CAPACITY_BYTES);
                        values.putNull(Root.COLUMN_AVAILABLE_BYTES);
                    }
                    if (storageCursor.getCount() == 1 && values.containsKey(Root.COLUMN_TITLE)) {
                        storageCursor.moveToFirst();
                        // Add storage name to device name if we have only 1 storage.
                        values.put(
                                Root.COLUMN_TITLE,
                                resources.getString(
                                        R.string.root_name,
                                        values.getAsString(Root.COLUMN_TITLE),
                                        storageCursor.getString(
                                                storageCursor.getColumnIndex(Root.COLUMN_TITLE))));
                    }
                } finally {
                    storageCursor.close();
                }

                final RowBuilder row = result.newRow();
                for (final String key : values.keySet()) {
                    row.add(key, values.get(key));
                }
            }

            return result;
        } finally {
            deviceCursor.close();
        }
    }

    /**
     * Queries root documents information.
     * @param columnNames Column names defined in
     *     {@link android.provider.DocumentsContract.Document}.
     * @return Database cursor.
     */
    @VisibleForTesting
    Cursor queryRootDocuments(String[] columnNames) {
        return mDatabase.query(
                TABLE_DOCUMENTS,
                columnNames,
                COLUMN_ROW_STATE + " IN (?, ?) AND " + COLUMN_DOCUMENT_TYPE + " = ?",
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED, DOCUMENT_TYPE_STORAGE),
                null,
                null,
                null);
    }

    /**
     * Queries documents information.
     * @param columnNames Column names defined in
     *     {@link android.provider.DocumentsContract.Document}.
     * @return Database cursor.
     */
    Cursor queryChildDocuments(String[] columnNames, String parentDocumentId) {
        return mDatabase.query(
                TABLE_DOCUMENTS,
                columnNames,
                COLUMN_ROW_STATE + " IN (?, ?) AND " + COLUMN_PARENT_DOCUMENT_ID + " = ?",
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED, parentDocumentId),
                null,
                null,
                null);
    }

    /**
     * Returns identifier of single storage if given document points device and it has only one
     * storage. Otherwise null.
     *
     * @param documentId Document ID that may point a device.
     * @return Identifier for single storage or null.
     * @throws FileNotFoundException The given document ID is not registered in database.
     */
    @Nullable Identifier getSingleStorageIdentifier(String documentId)
            throws FileNotFoundException {
        // Check if the parent document is device that has single storage.
        try (final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(Document.COLUMN_DOCUMENT_ID),
                COLUMN_ROW_STATE + " IN (?, ?) AND " +
                COLUMN_PARENT_DOCUMENT_ID + " = ? AND " +
                COLUMN_DOCUMENT_TYPE + " = ?",
                strings(ROW_STATE_VALID,
                        ROW_STATE_INVALIDATED,
                        documentId,
                        DOCUMENT_TYPE_STORAGE),
                null,
                null,
                null)) {
            if (cursor.getCount() == 1) {
                cursor.moveToNext();
                return createIdentifier(cursor.getString(0));
            } else {
                return null;
            }
        }
    }

    /**
     * Queries a single document.
     * @param documentId
     * @param projection
     * @return Database cursor.
     */
    Cursor queryDocument(String documentId, String[] projection) {
        return mDatabase.query(
                TABLE_DOCUMENTS,
                projection,
                SELECTION_DOCUMENT_ID,
                strings(documentId),
                null,
                null,
                null,
                "1");
    }

    @Nullable String getDocumentIdForDevice(int deviceId) {
        final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(Document.COLUMN_DOCUMENT_ID),
                COLUMN_DOCUMENT_TYPE + " = ? AND " + COLUMN_DEVICE_ID + " = ?",
                strings(DOCUMENT_TYPE_DEVICE, deviceId),
                null,
                null,
                null,
                "1");
        try {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            } else {
                return null;
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Obtains parent identifier.
     * @param documentId
     * @return parent identifier.
     * @throws FileNotFoundException
     */
    Identifier getParentIdentifier(String documentId) throws FileNotFoundException {
        final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(COLUMN_PARENT_DOCUMENT_ID),
                SELECTION_DOCUMENT_ID,
                strings(documentId),
                null,
                null,
                null,
                "1");
        try {
            if (cursor.moveToNext()) {
                return createIdentifier(cursor.getString(0));
            } else {
                throw new FileNotFoundException("Cannot find a row having ID = " + documentId);
            }
        } finally {
            cursor.close();
        }
    }

    String getDeviceDocumentId(int deviceId) throws FileNotFoundException {
        try (final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(Document.COLUMN_DOCUMENT_ID),
                COLUMN_DEVICE_ID + " = ? AND " + COLUMN_DOCUMENT_TYPE + " = ? AND " +
                COLUMN_ROW_STATE + " != ?",
                strings(deviceId, DOCUMENT_TYPE_DEVICE, ROW_STATE_DISCONNECTED),
                null,
                null,
                null,
                "1")) {
            if (cursor.getCount() > 0) {
                cursor.moveToNext();
                return cursor.getString(0);
            } else {
                throw new FileNotFoundException("The device ID not found: " + deviceId);
            }
        }
    }

    /**
     * Adds new document under the parent.
     * The method does not affect invalidated and pending documents because we know the document is
     * newly added and never mapped with existing ones.
     * @param parentDocumentId
     * @param info
     * @return Document ID of added document.
     */
    String putNewDocument(int deviceId, String parentDocumentId, MtpObjectInfo info) {
        final ContentValues values = new ContentValues();
        getObjectDocumentValues(values, deviceId, parentDocumentId, info);
        mDatabase.beginTransaction();
        try {
            final long id = mDatabase.insert(TABLE_DOCUMENTS, null, values);
            mDatabase.setTransactionSuccessful();
            return Long.toString(id);
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Deletes document and its children.
     * @param documentId
     */
    void deleteDocument(String documentId) {
        deleteDocumentsAndRootsRecursively(SELECTION_DOCUMENT_ID, strings(documentId));
    }

    /**
     * Gets identifier from document ID.
     * @param documentId Document ID.
     * @return Identifier.
     * @throws FileNotFoundException
     */
    Identifier createIdentifier(String documentId) throws FileNotFoundException {
        // Currently documentId is old format.
        final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(COLUMN_DEVICE_ID,
                        COLUMN_STORAGE_ID,
                        COLUMN_OBJECT_HANDLE,
                        COLUMN_DOCUMENT_TYPE),
                SELECTION_DOCUMENT_ID,
                strings(documentId),
                null,
                null,
                null,
                "1");
        try {
            if (cursor.getCount() == 0) {
                throw new FileNotFoundException("ID is not found.");
            } else {
                cursor.moveToNext();
                return new Identifier(
                        cursor.getInt(0),
                        cursor.getInt(1),
                        cursor.getInt(2),
                        documentId,
                        cursor.getInt(3));
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Deletes a document, and its root information if the document is a root document.
     * @param selection Query to select documents.
     * @param args Arguments for selection.
     * @return Whether the method deletes rows.
     */
    boolean deleteDocumentsAndRootsRecursively(String selection, String[] args) {
        mDatabase.beginTransaction();
        try {
            boolean changed = false;
            final Cursor cursor = mDatabase.query(
                    TABLE_DOCUMENTS,
                    strings(Document.COLUMN_DOCUMENT_ID),
                    selection,
                    args,
                    null,
                    null,
                    null);
            try {
                while (cursor.moveToNext()) {
                    if (deleteDocumentsAndRootsRecursively(
                            COLUMN_PARENT_DOCUMENT_ID + " = ?",
                            strings(cursor.getString(0)))) {
                        changed = true;
                    }
                }
            } finally {
                cursor.close();
            }
            if (deleteDocumentsAndRoots(selection, args)) {
                changed = true;
            }
            mDatabase.setTransactionSuccessful();
            return changed;
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Marks the documents and their child as disconnected documents.
     * @param selection
     * @param args
     * @return True if at least one row is updated.
     */
    boolean disconnectDocumentsRecursively(String selection, String[] args) {
        mDatabase.beginTransaction();
        try {
            boolean changed = false;
            try (final Cursor cursor = mDatabase.query(
                    TABLE_DOCUMENTS,
                    strings(Document.COLUMN_DOCUMENT_ID),
                    selection,
                    args,
                    null,
                    null,
                    null)) {
                while (cursor.moveToNext()) {
                    if (disconnectDocumentsRecursively(
                            COLUMN_PARENT_DOCUMENT_ID + " = ?",
                            strings(cursor.getString(0)))) {
                        changed = true;
                    }
                }
            }
            if (disconnectDocuments(selection, args)) {
                changed = true;
            }
            mDatabase.setTransactionSuccessful();
            return changed;
        } finally {
            mDatabase.endTransaction();
        }
    }

    boolean deleteDocumentsAndRoots(String selection, String[] args) {
        mDatabase.beginTransaction();
        try {
            int deleted = 0;
            deleted += mDatabase.delete(
                    TABLE_ROOT_EXTRA,
                    Root.COLUMN_ROOT_ID + " IN (" + SQLiteQueryBuilder.buildQueryString(
                            false,
                            TABLE_DOCUMENTS,
                            new String[] { Document.COLUMN_DOCUMENT_ID },
                            selection,
                            null,
                            null,
                            null,
                            null) + ")",
                    args);
            deleted += mDatabase.delete(TABLE_DOCUMENTS, selection, args);
            mDatabase.setTransactionSuccessful();
            // TODO Remove mappingState.
            return deleted != 0;
        } finally {
            mDatabase.endTransaction();
        }
    }

    boolean disconnectDocuments(String selection, String[] args) {
        mDatabase.beginTransaction();
        try {
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_DISCONNECTED);
            values.putNull(COLUMN_DEVICE_ID);
            values.putNull(COLUMN_STORAGE_ID);
            values.putNull(COLUMN_OBJECT_HANDLE);
            final boolean updated = mDatabase.update(TABLE_DOCUMENTS, values, selection, args) != 0;
            mDatabase.setTransactionSuccessful();
            return updated;
        } finally {
            mDatabase.endTransaction();
        }
    }

    int getRowState(String documentId) throws FileNotFoundException {
        try (final Cursor cursor = mDatabase.query(
                TABLE_DOCUMENTS,
                strings(COLUMN_ROW_STATE),
                SELECTION_DOCUMENT_ID,
                strings(documentId),
                null,
                null,
                null)) {
            if (cursor.getCount() == 0) {
                throw new FileNotFoundException();
            }
            cursor.moveToNext();
            return cursor.getInt(0);
        }
    }

    private static class OpenHelper extends SQLiteOpenHelper {
        public OpenHelper(Context context, int flags) {
            super(context,
                  flags == FLAG_DATABASE_IN_MEMORY ? null : DATABASE_NAME,
                  null,
                  DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(QUERY_CREATE_DOCUMENTS);
            db.execSQL(QUERY_CREATE_ROOT_EXTRA);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE " + TABLE_DOCUMENTS);
            db.execSQL("DROP TABLE " + TABLE_ROOT_EXTRA);
            onCreate(db);
        }
    }

    @VisibleForTesting
    static void deleteDatabase(Context context) {
        context.deleteDatabase(DATABASE_NAME);
    }

    static void getDeviceDocumentValues(
            ContentValues values,
            ContentValues extraValues,
            MtpDeviceRecord device) {
        values.clear();
        values.put(COLUMN_DEVICE_ID, device.deviceId);
        values.putNull(COLUMN_STORAGE_ID);
        values.putNull(COLUMN_OBJECT_HANDLE);
        values.putNull(COLUMN_PARENT_DOCUMENT_ID);
        values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
        values.put(COLUMN_DOCUMENT_TYPE, DOCUMENT_TYPE_DEVICE);
        values.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        values.put(Document.COLUMN_DISPLAY_NAME, device.name);
        values.putNull(Document.COLUMN_SUMMARY);
        values.putNull(Document.COLUMN_LAST_MODIFIED);
        values.put(Document.COLUMN_ICON, R.drawable.ic_root_mtp);
        values.put(Document.COLUMN_FLAGS, 0);
        values.putNull(Document.COLUMN_SIZE);

        extraValues.clear();
        extraValues.put(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
        extraValues.putNull(Root.COLUMN_AVAILABLE_BYTES);
        extraValues.putNull(Root.COLUMN_CAPACITY_BYTES);
        extraValues.put(Root.COLUMN_MIME_TYPES, "");
    }

    /**
     * Gets {@link ContentValues} for the given root.
     * @param values {@link ContentValues} that receives values.
     * @param root Root to be converted {@link ContentValues}.
     */
    static void getStorageDocumentValues(
            ContentValues values,
            ContentValues extraValues,
            String parentDocumentId,
            MtpRoot root) {
        values.clear();
        values.put(COLUMN_DEVICE_ID, root.mDeviceId);
        values.put(COLUMN_STORAGE_ID, root.mStorageId);
        values.putNull(COLUMN_OBJECT_HANDLE);
        values.put(COLUMN_PARENT_DOCUMENT_ID, parentDocumentId);
        values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
        values.put(COLUMN_DOCUMENT_TYPE, DOCUMENT_TYPE_STORAGE);
        values.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        values.put(Document.COLUMN_DISPLAY_NAME, root.mDescription);
        values.putNull(Document.COLUMN_SUMMARY);
        values.putNull(Document.COLUMN_LAST_MODIFIED);
        values.put(Document.COLUMN_ICON, R.drawable.ic_root_mtp);
        values.put(Document.COLUMN_FLAGS, 0);
        values.put(Document.COLUMN_SIZE, root.mMaxCapacity - root.mFreeSpace);

        extraValues.put(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
        extraValues.put(Root.COLUMN_AVAILABLE_BYTES, root.mFreeSpace);
        extraValues.put(Root.COLUMN_CAPACITY_BYTES, root.mMaxCapacity);
        extraValues.put(Root.COLUMN_MIME_TYPES, "");
    }

    /**
     * Gets {@link ContentValues} for the given MTP object.
     * @param values {@link ContentValues} that receives values.
     * @param deviceId Device ID of the object.
     * @param parentId Parent document ID of the object.
     * @param info MTP object info.
     */
    static void getObjectDocumentValues(
            ContentValues values, int deviceId, String parentId, MtpObjectInfo info) {
        values.clear();
        final String mimeType = getMimeType(info);
        int flag = 0;
        if (info.getProtectionStatus() == 0) {
            flag |= Document.FLAG_SUPPORTS_DELETE |
                    Document.FLAG_SUPPORTS_WRITE;
            if (mimeType == Document.MIME_TYPE_DIR) {
                flag |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        }
        if (info.getThumbCompressedSize() > 0) {
            flag |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }
        values.put(COLUMN_DEVICE_ID, deviceId);
        values.put(COLUMN_STORAGE_ID, info.getStorageId());
        values.put(COLUMN_OBJECT_HANDLE, info.getObjectHandle());
        values.put(COLUMN_PARENT_DOCUMENT_ID, parentId);
        values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
        values.put(COLUMN_DOCUMENT_TYPE, DOCUMENT_TYPE_OBJECT);
        values.put(Document.COLUMN_MIME_TYPE, mimeType);
        values.put(Document.COLUMN_DISPLAY_NAME, info.getName());
        values.putNull(Document.COLUMN_SUMMARY);
        values.put(
                Document.COLUMN_LAST_MODIFIED,
                info.getDateModified() != 0 ? info.getDateModified() : null);
        values.putNull(Document.COLUMN_ICON);
        values.put(Document.COLUMN_FLAGS, flag);
        values.put(Document.COLUMN_SIZE, info.getCompressedSize());
    }

    private static String getMimeType(MtpObjectInfo info) {
        if (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        final String formatCodeMimeType = MediaFile.getMimeTypeForFormatCode(info.getFormat());
        if (formatCodeMimeType != null) {
            return formatCodeMimeType;
        }
        final String mediaFileMimeType = MediaFile.getMimeTypeForFile(info.getName());
        if (mediaFileMimeType != null) {
            return mediaFileMimeType;
        }
        // We don't know the file type.
        return "application/octet-stream";
    }

    static String[] strings(Object... args) {
        final String[] results = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            results[i] = Objects.toString(args[i]);
        }
        return results;
    }
}
