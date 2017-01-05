/*
 * Copyright (c) 2012-2017: Christopher J. Brody (aka Chris Brody)
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

package io.sqlc;

import android.annotation.SuppressLint;

import android.util.Log;

import java.io.File;
import java.lang.IllegalArgumentException;
//import java.lang.Number;

import java.net.URI;
import java.net.URISyntaxException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

//import java.io.FileOutputStream;
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.io.IOException;

import java.sql.SQLException;

import org.apache.cordova.PluginResult;

public class SQLitePlugin extends CordovaPlugin {

    /**
     * Multiple database runner map (static).
     * NOTE: no public static accessor to db (runner) map since it would not work with db threading.
     * FUTURE put DBRunner into a public class that can provide external accessor.
     */
    static ConcurrentHashMap<String, DBRunner> dbrmap = new ConcurrentHashMap<String, DBRunner>();
    static ConcurrentHashMap<Integer, DBRunner> dbrmap2 = new ConcurrentHashMap<Integer, DBRunner>();

    static int lastdbid = 0;

    /**
     * NOTE: Using default constructor, no explicit constructor.
     */

    @Override
    public boolean execute(String actionAsString, String argsAsString, CallbackContext cbc) {
        if (actionAsString.startsWith("fj")) {
            int sep1pos = actionAsString.indexOf(':');
            int sep2pos = actionAsString.indexOf(';');

            int ll = Integer.parseInt(actionAsString.substring(sep1pos+1, sep2pos));
            ll += 10; // plus overhead with extra space extra space

            int s1pos = argsAsString.indexOf('[');
            int s2pos = argsAsString.indexOf(',');
            int dbid = Integer.parseInt(argsAsString.substring(s1pos+1, s2pos));

            // put db query in the queue to be executed in the db thread:
            DBQuery q = new DBQuery(argsAsString, ll, cbc);
            DBRunner r = dbrmap2.get(dbid);
            if (r != null) {
                try {
                    r.q.put(q);
                } catch(Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                    cbc.error("couldn't add to queue");
                }
            } else {
                cbc.error("database not open");
            }
            return true;
        } else {
            try {
                return execute(actionAsString, new JSONArray(argsAsString), cbc);
            } catch (JSONException e) {
                // TODO: signal JSON problem to JS
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
                return false;
            }
        }
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args   JSONArry of arguments for the plugin.
     * @param cbc    Callback context from Cordova API
     * @return       Whether the action was valid.
     */
    @Override
    public boolean execute(String actionAsString, JSONArray args, CallbackContext cbc) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, cbc);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

        boolean status = true;
        JSONObject o;
        String echo_value;
        String dbname;

        switch (action) {
            case echoStringValue:
                o = args.getJSONObject(0);
                echo_value = o.getString("value");
                cbc.success(echo_value);
                break;

            case open:
                o = args.getJSONObject(0);
                dbname = o.getString("name");
                // open database and start reading its queue
                this.startDatabase(dbname, o, cbc);
                break;

            case close:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                // put request in the q to close the db
                this.closeDatabase(dbname, cbc);
                break;

            case delete:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                String dblocation = null;
                if (o.has("androidDatabaseLocation"))
                    dblocation = o.getString("androidDatabaseLocation");

                deleteDatabase(dbname, dblocation, cbc);

                break;

            case executeSqlBatch:
            case backgroundExecuteSqlBatch:
                JSONObject allargs = args.getJSONObject(0);
                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    cbc.error("missing executes list");
                } else {
                    int len = txargs.length();
                    String[] queries = new String[len];
                    JSONArray[] jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        jsonparams[i] = a.getJSONArray("params");
                    }

                    // put db query in the queue to be executed in the db thread:
                    DBQuery q = new DBQuery(queries, jsonparams, cbc);
                    DBRunner r = dbrmap.get(dbname);
                    if (r != null) {
                        try {
                            r.q.put(q);
                        } catch(Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                            cbc.error("couldn't add to queue");
                        }
                    } else {
                        cbc.error("database not open");
                    }
                }
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        while (!dbrmap.isEmpty()) {
            String dbname = dbrmap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DBRunner r = dbrmap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DBQuery());
            } catch(Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't stop db thread", e);
            }
            dbrmap.remove(dbname);
            dbrmap2.remove(r.dbid);
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, JSONObject options, CallbackContext cbc) {
        // TODO: is it an issue that we can orphan an existing thread?  What should we do here?
        // If we re-use the existing DBRunner it might be in the process of closing...
        DBRunner r = dbrmap.get(dbname);

        // Brody TODO: It may be better to terminate the existing db thread here & start a new one, instead.
        if (r != null) {
            // don't orphan the existing thread; just re-open the existing database.
            // In the worst case it might be in the process of closing, but even that's less serious
            // than orphaning the old DBRunner.

            if (r.oldImpl) {
              // Android version with 'normal' JSON interface:
              cbc.success();
            } else {
              try {
                // Android version with flat JSON interface:
                JSONObject a1 = new JSONObject();
                a1.put("dbid", r.dbid);
                cbc.success(a1);
              } catch(JSONException e) {
                // NOT EXPECTED:
                cbc.error("Internal error");
              }
            }
        } else {
            r = new DBRunner(dbname, options, cbc, ++lastdbid);
            dbrmap.put(dbname, r);
            dbrmap2.put(r.dbid, r);
            this.cordova.getThreadPool().execute(r);
        }
    }

    /**
     * Get a database file.
     *
     * @param dbName   The name of the database file
     */
    private File getDatabaseFile(String dbname, String dblocation) throws URISyntaxException {
        if (dblocation == null) {
            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            return dbfile;
        }

        return new File(new File(new URI(dblocation)), dbname);
    }

    /**
     * Open a database.
     *
     * @param dbName   The name of the database file
     */
    private SQLiteNativeDatabase openDatabase(String dbname, String dblocation, CallbackContext cbc, boolean old_impl, int dbid) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = getDatabaseFile(dbname, dblocation);
            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteNativeDatabase mydb = new SQLiteNativeDatabase();
            mydb.open(dbfile);

            // Indicate Android version with flat JSON interface
            JSONObject a1 = new JSONObject();
            a1.put("dbid", dbid);
            cbc.success(a1);

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    private SQLiteAndroidDatabase openDatabase2(String dbname, String dblocation, CallbackContext cbc, boolean old_impl) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = getDatabaseFile(dbname, dblocation);
            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteAndroidDatabase mydb = new SQLiteAndroidDatabase();
            mydb.open(dbfile);

            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.success(); // (TBD) Indicate Android version with normal JSON interface

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    /**
     * Close a database (in another thread).
     *
     * @param dbName   The name of the database file
     */
    private void closeDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(false, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            if (cbc != null) {
                cbc.success();
            }
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname   The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteAndroidDatabase mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    private void deleteDatabase(String dbname, String dblocation, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(true, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            boolean deleteResult = this.deleteDatabaseNow(dbname, dblocation);
            if (deleteResult) {
                cbc.success();
            } else {
                cbc.error("couldn't delete database");
            }
        }
    }

    /**
     * Delete a database.
     *
     * @param dbName   The name of the database file
     *
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabaseNow(String dbname, String dblocation) {
        try {
            File dbfile = getDatabaseFile(dbname, dblocation);

            return cordova.getActivity().deleteDatabase(dbfile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    static boolean isNativeLibLoaded = false;

    class SQLiteNativeDatabase extends SQLiteAndroidDatabase {
        long mydbhandle;

        /**
         * Open a database.
         *
         * @param dbFile   The database File specification
         */
        @Override
        void open(File dbFile) throws Exception {
            if (!isNativeLibLoaded) {
                System.loadLibrary("sqlc-evcore-native-driver");
                isNativeLibLoaded = true;
            }

            mydbhandle = EVCoreNativeDriver.sqlc_evcore_db_open(EVCoreNativeDriver.SQLC_EVCORE_API_VERSION,
              dbFile.getAbsolutePath(),
              EVCoreNativeDriver.SQLC_OPEN_READWRITE | EVCoreNativeDriver.SQLC_OPEN_CREATE);

            if (mydbhandle < 0) throw new SQLException("open error", "failed", -(int)mydbhandle);
        }

        /**
         * Close a database (in the current thread).
         */
        @Override
        void closeDatabaseNow() {
            try {
                if (mydbhandle > 0) EVCoreNativeDriver.sqlc_db_close(mydbhandle);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database, ignoring", e);
            }
        }

        /**
         * Ignore Android bug workaround for native version
         */
        @Override
        void bugWorkaround() { }

        String flatBatchJSON(String batch_json, int ll) {
            long ch = EVCoreNativeDriver.sqlc_evcore_db_new_qc(mydbhandle);
            String jr = EVCoreNativeDriver.sqlc_evcore_qc_execute(ch, batch_json, ll);
            EVCoreNativeDriver.sqlc_evcore_qc_finalize(ch);
            return jr;
        }
    }

    private class DBRunner implements Runnable {
        final int dbid;
        final String dbname;
        final String dblocation;
        // expose oldImpl:
        boolean oldImpl;
        private boolean bugWorkaround;

        final BlockingQueue<DBQuery> q;
        final CallbackContext openCbc;

        SQLiteNativeDatabase mydb1;
        SQLiteAndroidDatabase mydb;

        DBRunner(final String dbname, JSONObject options, CallbackContext cbc, int dbid) {
            this.dbid = dbid;
            this.dbname = dbname;
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            //Log.v(SQLitePlugin.class.getSimpleName(), "Android db implementation: built-in android.database.sqlite package");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");

            String mydblocation = null;
            if (options.has("androidDatabaseLocation")) {
                try {
                    mydblocation = options.getString("androidDatabaseLocation");
                } catch (Exception e) {
                    // IGNORED
                    Log.e(SQLitePlugin.class.getSimpleName(), "unexpected JSON exception, IGNORED", e);
                }
            }
            this.dblocation = mydblocation;

            if (this.bugWorkaround)
                Log.v(SQLitePlugin.class.getSimpleName(), "Android db closing/locking workaround applied");

            this.q = new LinkedBlockingQueue<DBQuery>();
            this.openCbc = cbc;
        }

        public void run() {
            try {
                if (!oldImpl)
                    this.mydb = this.mydb1 = openDatabase(dbname, dblocation, this.openCbc, this.oldImpl, this.dbid);
                else
                    this.mydb = openDatabase2(dbname, dblocation, this.openCbc, this.oldImpl);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbrmap.remove(dbname);
                dbrmap2.remove(dbid);
                return;
            }

            DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    if (oldImpl) {
                        mydb.executeSqlBatch(dbq.queries, dbq.jsonparams, dbq.cbc);
                    } else {
                        dbq.cbc.sendPluginResult(new MyPluginResult(mydb1.flatBatchJSON(dbq.fj, dbq.ll)));
                    }

                    if (this.oldImpl && this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                        mydb.bugWorkaround();

                    dbq = q.take();
                }
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            }

            if (dbq != null && dbq.close) {
                try {
                    closeDatabaseNow(dbname);

                    dbrmap.remove(dbname); // (should) remove ourself
                    dbrmap.remove(dbid); // (should) remove ourself

                    if (!dbq.delete) {
                        dbq.cbc.success();
                    } else {
                        try {
                            boolean deleteResult = deleteDatabaseNow(dbname, dblocation);
                            if (deleteResult) {
                                dbq.cbc.success();
                            } else {
                                dbq.cbc.error("couldn't delete database");
                            }
                        } catch (Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
                            dbq.cbc.error("couldn't delete database: " + e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
                    if (dbq.cbc != null) {
                        dbq.cbc.error("couldn't close database: " + e);
                    }
                }
            }
        }
    }

    private class MyPluginResult extends PluginResult {
        final String jr;

        MyPluginResult(String jr) {
            super(PluginResult.Status.OK);
            this.jr = jr;
        }

        @Override
        public int getMessageType() { return PluginResult.MESSAGE_TYPE_JSON; }

        @Override
        public String getMessage() { return jr; }
    }

    private final class DBQuery {
        // XXX TODO replace with DBRunner action enum:
        final boolean stop;
        final boolean close;
        final boolean delete;
        final int ll;
        final String fj;
        final String[] queries;
        final JSONArray[] jsonparams;
        final CallbackContext cbc;

        DBQuery(String[] myqueries, JSONArray[] params, CallbackContext c) {
            this.fj = null;
            this.ll = -1;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.jsonparams = params;
            this.cbc = c;
        }

        DBQuery(String fj, int ll, CallbackContext c) {
            this.fj = fj;
            this.ll = ll;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = c;
        }

        DBQuery(boolean delete, CallbackContext cbc) {
            this.fj = null;
            this.ll = -1;
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = cbc;
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.fj = null;
            this.ll = -1;
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = null;
        }
    }

    private static enum Action {
        echoStringValue,
        open,
        close,
        delete,
        executeSqlBatch,
        backgroundExecuteSqlBatch,
    }
}

/* vim: set expandtab : */
