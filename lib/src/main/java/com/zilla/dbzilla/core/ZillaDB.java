/*
 * Copyright 2017. Zilla Chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zilla.dbzilla.core;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.text.TextUtils;

import com.github.snowdream.android.util.Log;
import com.zilla.dbzilla.core.util.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manage the CURD of table,this is a singleton pattern.
 * now support the sql field type:String,int,float and double.
 * <br>
 * 管理数据库的增删改查, 该类使用单例，为了防止线程冲突，所有方法改用同步实现
 * 数据库字段类型支持String,int,float,double
 *
 * @author ze.chen
 */
public class ZillaDB {

    private static ZillaDB zillaDb;

    private SQLiteDatabase database;

    private static boolean isClosed = false;

    /**
     * ReadWriteLock
     */
    ReadWriteLock lock = new ReentrantReadWriteLock();

    private DBHelper dbHelper = DBHelper.getInstance();

    private ZillaDB() {
        database = dbHelper.getWritableDatabase();
    }

    public static ZillaDB getInstance() {
        if (zillaDb == null || isClosed) {
            zillaDb = new ZillaDB();
            isClosed = false;
        }
        return zillaDb;
    }

    /**
     * insert a row
     * <br>
     * 插入一条记录
     *
     * @param model pojo model
     * @return boolean
     */
    @SuppressLint("NewApi")
    public boolean save(Object model) {
        lock.writeLock().lock();

        long id = 0;
        String tableName = null;
        ContentValues value = null;

        try {
            filter(model.getClass());
            tableName = AnnotationUtil.getTableName(model.getClass());
            value = AnnotationUtil.model2ContentValues(database, model, tableName);
            if (0 == AnnotationUtil.getAutoIdValue(model)) {
                id = saveOnKeyConflict(model, value, tableName);
            } else {
                try {
                    // 添加异常处理，如果插入冲突，改为update
                    id = database.insertWithOnConflict(tableName, "", value,
                            SQLiteDatabase.CONFLICT_NONE);//主键冲突策略，替换掉以往的数据
                } catch (SQLiteConstraintException e) {//主键冲突
                    id = saveOnKeyConflict(model, value, tableName);
                }
            }
        } finally {
            AnnotationUtil.setAutoIdValue(model, id);
            lock.writeLock().unlock();
        }
        return true;
    }


    @SuppressLint("NewApi")
    private void save4List(SQLiteStatement stat,Object model) {
//        String tableName = AnnotationUtil.getTableName(model.getClass());
//        ContentValues value = AnnotationUtil.model2ContentValues(database, model, tableName);
//        if (0 == AnnotationUtil.getAutoIdValue(model)) {
//            saveOnKeyConflict(model, value, tableName);
//        } else {
//            // 添加异常处理，如果插入冲突，改为update
//            database.insertWithOnConflict(tableName, "", value,
//                    SQLiteDatabase.CONFLICT_NONE);//主键冲突策略，替换掉以往的数据
//        }


        stat.clearBindings();
        //bind
        List<ModelProperty> modelProperties = ModelHolder.getProperties(model.getClass());
        for (int i = 0, l = modelProperties.size(); i < l; i++) {
            ModelProperty modelProperty = modelProperties.get(i);
            Object fieldValue = ReflectUtil.getFieldValue(model, modelProperty.getField());
            Class<?> type = modelProperty.getField().getType();
            if (String.class == type) {
                if (fieldValue == null) {
                    stat.bindNull(i + 1);
                } else {
                    stat.bindString(i + 1, (String) fieldValue);
                }
            } else if (long.class == type) {
                stat.bindLong(i + 1, (Long) fieldValue);
            } else if (double.class == type) {
                stat.bindDouble(i + 1, (Double) fieldValue);
            } else if (int.class == type) {
                stat.bindLong(i + 1, (Integer) fieldValue);
            } else if (short.class == type) {
                stat.bindDouble(i + 1, (Short) fieldValue);
            } else if (boolean.class == type) {
                stat.bindLong(i + 1, (Boolean) fieldValue ? 1 : 0);
            } else {
                stat.bindString(i + 1, (String) fieldValue);
            }
        }
        stat.executeInsert();


//        stat.bindAllArgsAsStrings();
//        stat.bindLong(1, memberInfo.getGroupID());
//        stat.bindString(2, memberInfo.getMemberSubsID());
//        stat.bindString(3, memberInfo.getMemberNickName());
//        stat.bindLong(4, memberInfo.getMemberRole());
//        stat.bindString(5, memberInfo.getMemberJID());
//        stat.bindString(6, memberInfo.getMemUnionKey());
//        stat.bindString(7, convertDate2String(memberInfo.getCreatTime()));
//        stat.bindString(8, convertDate2String(memberInfo.getUpdateTime()));
//        stat.bindString(9, memberInfo.getOrderID());
//        stat.bindString(10, memberInfo.getExt());
    }

    /**
     * when save conflict,try remove the primary key and save again.
     *
     * @param model
     * @param value
     * @param tableName
     * @return
     */
    private long saveOnKeyConflict(Object model, ContentValues value, String tableName) {
        long result = 0;
        try {
            String key = AnnotationUtil.getIdName(model.getClass());
            value.remove(key);

            result = database.insertWithOnConflict(tableName, "", value,
                    SQLiteDatabase.CONFLICT_REPLACE);//主键冲突策略，替换掉以往的数据
            Log.i("INSERT'" + tableName + "'success.");

        } catch (SQLiteConstraintException e1) {
            Log.e("INSERT '" + tableName + "' failed.", e1);
        } catch (SQLiteException sqLiteException) {
            Log.e("INSERT '" + tableName + "' failed.", sqLiteException);
        } catch (Exception ex) {
            Log.e("INSERT '" + tableName + "' failed.", ex);
        }
        return result;
    }

    /**
     * insert multi-row.
     * <br>
     * 插入多条记录
     *
     * @param list the list to be saved
     * @return boolean
     */
    public boolean saveList(List<?> list) {
        lock.writeLock().lock();
        if (list == null || list.size() == 0) {
            return false;
        }
        try {
            Object temp = list.get(0);
            filter(temp.getClass());
            database.beginTransaction();

            String sql = ModelHolder.getInsetSQL(temp.getClass());

            SQLiteStatement stat = database.compileStatement(sql);

            for (int i = 0, l = list.size(); i < l; i++) {
                save4List(stat,list.get(i));
            }
            database.setTransactionSuccessful();// 必须执行该方法，否则事务会回滚
            database.endTransaction();
        } catch (Exception e) {
            Log.e("saveList", e);
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * delete a row
     * <br>
     * 根据传入的模型的主键删除记录
     *
     * @param model the pojo model
     * @return the number of rows affected
     */
    public int delete(Object model) {
        lock.writeLock().lock();
        int row = 0;
        try {
            filter(model.getClass());
            String key = AnnotationUtil.getIdName(model.getClass());
            row = database.delete(AnnotationUtil.getTableName(model.getClass()), key + " = ? ", new String[]{ReflectUtil.getFieldValue(model, key).toString()});
        } catch (Exception e) {
            Log.e("" + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return row;
    }

    /**
     * delete all rows
     * <br>
     * 删除表中的所有数据
     *
     * @param c Tpye
     * @return boolean
     */
    public int deleteAll(Class c) {
        int rows = 0;
        lock.writeLock().lock();
        try {
            filter(c);
            rows = database.delete(AnnotationUtil.getTableName(c), null, null);
        } catch (Exception e) {
            Log.e("" + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return rows;

    }

    public int delete(Class c, String whereClause, String[] whereArgs) {
        int rows = 0;
        lock.writeLock().lock();
        try {
            filter(c);
            rows = database.delete(AnnotationUtil.getTableName(c), whereClause, whereArgs);
        } catch (Exception e) {
            Log.e("" + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return rows;
    }

    @SuppressLint("NewApi")
    private boolean update(String tableName, Object model, String columnName, Cursor cursor) {
        lock.writeLock().lock();
        try {
            filter(model.getClass());
            String key = columnName;
            if (TextUtils.isEmpty(key)) {
                key = AnnotationUtil.getIdName(model.getClass());
            }
            String keyValue = ReflectUtil.getFieldValue(model, key).toString();
            if (TextUtils.isEmpty(keyValue)) {// 如果更新的主键为空，则插入该条记录
                save(model);
                return false;
            }
            ContentValues value = model2ContentValues(model, cursor);
            value.remove(key);
            int rowId = this.database.update(tableName, value, key + " = ? ", new String[]{keyValue});
            if (0 == rowId) {
                return false;
            }
        } catch (Exception e) {
            Log.e("" + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * update a row by model's key
     * <br>
     * 根据主键更新数据
     *
     * @param model the pojo model
     * @return boolean
     */
    public boolean update(Object model) {
        lock.writeLock().lock();
        String tableName = null;
        String key;
        String keyValue;
        Cursor cursor = null;
        ContentValues value = null;
        try {
            filter(model.getClass());
            tableName = AnnotationUtil.getTableName(model.getClass());
            key = AnnotationUtil.getIdName(model.getClass());
            keyValue = ReflectUtil.getFieldValue(model, key).toString();
            cursor = database.query(tableName, null, null, null, null, null, null, "1");
            value = model2ContentValues(model, cursor);
            // 更新时不能更新主键,这是数据库主键设计原则{
            value.remove(key);
            // }
            int rows = this.database.update(tableName, value, key + " = ? ", new String[]{keyValue});
            if (0 == rows) {
                save(model);
            }
        } catch (Exception e) {
            Log.e("更新表'" + tableName + "'数据出现异常,将执行添加操作:" + e);
            try {

                database.insertWithOnConflict(tableName, "", value,
                        SQLiteDatabase.CONFLICT_REPLACE);//主键冲突策略，替换掉以往的数据
            } catch (Exception e1) {
                Log.e("插入表'" + tableName + "'数据失败:" + e1.getMessage());
            }
        } finally {
            closeCursor(cursor);
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * android's normal update method
     * <br>
     * 原生更新方法
     *
     * @param table       the table
     * @param values      ContentValues
     * @param whereClause where string
     * @param whereArgs   where list
     * @return the number of rows affected
     */
    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
        lock.writeLock().lock();
        int row = 0;
        try {
            row = this.database.update(table, values, whereClause, whereArgs);
        } catch (Exception e) {
            Log.e("" + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return row;
    }

    /**
     * merge the changes field to the row.
     * <br>
     * 合并操作，合并该记录中发生变动的字段到该记录。
     * <br>场景：更新记录中的某几个字段
     *
     * @param model       pojo model
     * @param whereClause where string
     * @param whereArgs   where list
     * @return if merge success
     */
    public boolean merge(Object model, String whereClause, String[] whereArgs) {
//        lock.writeLock().lock();
        Object local = find(model.getClass(), whereClause, whereArgs);
        if (local == null) {
            return save(model);
        }
//        String[] keys = ReflectUtil.getFields(model);
        Field[] fields = model.getClass().getDeclaredFields();
        try {
            for (Field field : fields) {
                if (Modifier.FINAL == field.getModifiers()) break; //如果是final，跳过
                Object obj = field.get(model);
                if (obj == null) {
                    break;
                }
                ReflectUtil.setFieldValue(local, field.getName(), obj);
            }
            update(local);
        } catch (Exception e) {
            Log.e(e.getMessage());
            return false;
        } finally {
//            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * convert a model to contentvalues
     * <br>
     * 将model转化为ContentValues
     *
     * @param model  pojo model
     * @param cursor cursor
     * @return ContentValues
     */

    private ContentValues model2ContentValues(Object model, Cursor cursor) {
        ContentValues value = new ContentValues();
        String[] names = cursor.getColumnNames();
        for (int i = 0, l = names.length; i < l; i++) {
            try {
                String name = names[i];
                value.put(name,
                        ReflectUtil.getFieldValue(model, ModelHolder.getFieldByName(model.getClass(), name)).toString());
            } catch (Exception e) {

            }
        }
        return value;
    }

    /**
     * delete all and add all from a list
     * <br>
     * 删除表中所有数据, 并重新插入数据
     *
     * @param c    Type
     * @param list the list to be saved
     * @return boolean
     */
    public boolean updateAll(Class<?> c, List<?> list) {
        lock.writeLock().lock();
        try {
            deleteAll(c);
            saveList(list);
        } catch (Exception e) {
            Log.e(e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * batch update rows,if the key is empty,insert it.
     * <br>
     * 批量更新已经存在的数据,如果主键值为空，则插入
     *
     * @param list the list to be updated
     * @return boolean
     */
    public boolean update(List<?> list) {
        lock.writeLock().lock();
        if (list == null || list.size() == 0) {
            return false;
        }
        Object temp = list.get(0);
        filter(temp.getClass());
        String tableName = AnnotationUtil.getTableName(temp.getClass());
        Cursor cursor = null;
        try {
            cursor = database.query(tableName, null, null, null, null, null, null, "1");
            for (int i = 0, l = list.size(); i < l; i++) {
                Object model = list.get(i);
                update(tableName, model, null, cursor);
            }
        } catch (Exception e) {
            Log.e(e.getMessage());
        } finally {
            closeCursor(cursor);
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * update table rows by table columnName(update if column's value is same)
     * <br>
     * 根据给定的字段名，批量更新数据库
     *
     * @param list       the list to be updated
     * @param columnName the key column
     * @return boolean
     */
    public boolean update(List<?> list, String columnName) {
        lock.writeLock().lock();
        if (list == null || list.size() == 0) {
            return false;
        }
        Object temp = list.get(0);
        filter(temp.getClass());
        String tableName = AnnotationUtil.getTableName(temp.getClass());
        Cursor cursor = null;
        try {
            cursor = database.query(tableName, null, null, null, null, null, null, "1");
            for (int i = 0, l = list.size(); i < l; i++) {
                Object model = list.get(i);
                update(tableName, model, columnName, cursor);
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
            lock.writeLock().unlock();
        }
        return true;
    }


    /**
     * convert cursor to model
     * <br>
     * 将游标所在位置的记录转化成模型
     *
     * @param cursor cursor
     * @return Type
     */
    private <T> T cursor2Model(Cursor cursor, Class<T> c) {
        T cloneModel = null;
        try {
            cloneModel = c.newInstance();
            String[] columnNames = cursor.getColumnNames();
            for (int i = 0, l = columnNames.length; i < l; i++) {
                //根据模型字段类型，从数据库中取相应的类型,优先采用字符串类型
                String columnName = columnNames[i];
                Field colneModelField = null;
                try {
                    colneModelField = cloneModel.getClass().getDeclaredField(columnName);
                } catch (NoSuchFieldException e) {
                    Log.e("Can't find field '" + columnName + "' in model " + c.getName() + ",continue...");
                    continue;
                }
                Class<?> type = colneModelField.getType();
                if (type == String.class) {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getString(i));
                } else if (type == int.class) {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getInt(i));
                } else if (type == long.class) {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getLong(i));
                } else if (type == float.class) {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getFloat(i));
                } else if (type == double.class) {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getDouble(i));
                } else {
                    ReflectUtil.setFieldValue(cloneModel, columnName, cursor.getString(i));
                }
            }
        } catch (InstantiationException e) {
            Log.e("Exception", e);
        } catch (IllegalAccessException e) {
            Log.e("Exception", e);
        }
        return cloneModel;
    }

    /**
     * find all rows from table
     * <br>
     * 查询表中所有记录，并转化成model数组
     *
     * @param c Type
     * @return model list
     */
    public <T> List<T> findAll(Class<T> c) {
        lock.readLock().lock();
        filter(c);
        String tableName = AnnotationUtil.getTableName(c);
        List<T> list = new ArrayList<T>();
        Cursor cursor = null;
        try {
            cursor = database.query(tableName, null, null, null, null, null, null);
            int count = cursor.getCount();
            if (count == 0) {
                return list;
            }
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                T cloneModel = cursor2Model(cursor, c);
                list.add(cloneModel);
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
            lock.readLock().unlock();
        }
        return list;
    }

    /**
     * find one row form table by key
     * <br>
     * 查询单条记录
     *
     * @param c  Type
     * @param id id
     * @return Object 没有查找到返回null
     */
    public <T> T findById(Class<T> c, Object id) {
        lock.readLock().lock();
        filter(c);
        String tableName = AnnotationUtil.getTableName(c);
        Cursor cursor = null;
        T result = null;
        String key = AnnotationUtil.getIdName(c);
        try {
            cursor = this.database.query(tableName, null, key + "=?", new String[]{String.valueOf(id)}, null, null, null);
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            result = cursor2Model(cursor, c);
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
            lock.readLock().unlock();
        }
        return result;
    }

    public <T> List<T> find(Class<T> c, String condition) {
        lock.readLock().lock();
        List<T> result = null;
        try {
            filter(c);
            result = find(c, condition, null, null, null);
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /**
     * find first row by the given condition
     * <br>
     * 根据条件查询一条记录
     *
     * @param c         Type
     * @param selection where string
     * @param condition where list
     * @return Object 没有查找到返回null
     */
    public <T> T find(Class<T> c, String selection, String[] condition) {
        lock.readLock().lock();
        T result = null;
        try {
            filter(c);
            List<T> items = find(c, selection, condition, null, "1");
            if (items != null && items.size() > 0) {
                result = items.get(0);
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /**
     * find rows form table DESC
     * <br>
     * 数据库查询, 默认情况下采用ID降序排列
     *
     * @param c         Type
     * @param selection where string
     * @param condition where list
     * @param limit     limit
     * @return model list
     */
    public <T> List<T> find(Class<T> c, String selection, String[] condition, String limit) {
        lock.readLock().lock();
        List<T> result = null;
        try {
            filter(c);
            result = find(c, selection, condition, null, limit);
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            lock.readLock().unlock();
        }
        return result;

    }

    public <T> List<T> find(Class<T> c, String selection, String[] condition, String orderBy, String limit) {
        lock.readLock().lock();
        filter(c);
        String tableName = AnnotationUtil.getTableName(c);
        List<T> list = new ArrayList<T>();
        Cursor cursor = null;
        try {
            cursor = database.query(tableName, null, selection, condition, null, null, orderBy, limit);
            int count = cursor.getCount();
            if (count == 0) {
                return list;
            }
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                T cloneModel = cursor2Model(cursor, c);
                list.add(cloneModel);
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
            lock.readLock().unlock();
        }
        return list;
    }

    /**
     * close cursor
     * <br>
     * 关闭游标
     *
     * @param cursor the cursor to be closed
     */
    private void closeCursor(Cursor cursor) {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }

    /**
     * close db
     * <br>
     * 关闭数据库
     */
    public void close() {
        this.database.close();
        isClosed = true;
    }

    /**
     * judge if table is exist.
     * 从sqlit_master中查询该表是否存在，如果缓存记录存在、sqlite_master表中存在 或者新建表，返回true
     *
     * @param c Type
     * @return if the table exist
     */
    public boolean isTableExist(Class c) {
        String tableName = AnnotationUtil.getTableName(c);
        // 如果已经判断过该表的已经存在
        if (TableHolder.isTableExist(tableName))
            return true;
        String sql = "SELECT COUNT(*) AS c FROM sqlite_master WHERE type ='table' AND name ='" + tableName + "'";
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(sql, null);
            if (cursor != null && cursor.moveToNext()) {
                int count = cursor.getInt(0);
                if (count > 0) {
                    TableHolder.addTable(tableName);
                    upgradeMerge(c);
                    return true;
                }
            }
            if (createTable(c)) {
                return true;
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
        }
        return false;
    }

    /**
     * auto update table field by model,when db version upgrade.
     * <br>
     * 版本升级时，如果模型改动，自动升级数据库表
     *
     * @param c Type
     */
    private void upgradeMerge(Class c) {
//        lock.writeLock().lock();
        Cursor cursor = null;
        try {
            String tableName = AnnotationUtil.getTableName(c);
            cursor = database.query(tableName, null, null, null, null, null, null, "1");
            ContentValues value = new ContentValues();
            String[] names = cursor.getColumnNames();//table fields
            Field[] modelProperties = c.getDeclaredFields();

            List<Field> fieldList = new ArrayList<Field>();
            for (Field field : modelProperties) {
                if (field.getModifiers() == Modifier.VOLATILE || field.getName().contains("change")) {
                } else {
                    fieldList.add(field);
                }
            }

            for (Field field : fieldList) {
                if (Modifier.FINAL == field.getModifiers()) break; //如果是final类型的，跳过
                boolean contains = false;
                for (String tableField : names) {
                    if (tableField.equals(field.getName())) {
                        contains = true;
                        break;
                    }
                }
                if (!contains) {//如果数据库表中没有该字段，则添加该字段
                    database.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + field.getName() + " " + getType(field));
                }
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
//            lock.writeLock().unlock();
        }

    }

    /**
     * filter,be called before CURD
     * <br>
     * 过滤器，在执行CURD之前调用该方法
     *
     * @param c Type
     */
    private void filter(Class c) {
        isTableExist(c);
    }

    /**
     * create table.
     * <br>
     * 创建表
     *
     * @param c Type
     * @return if success
     */
    public synchronized boolean createTable(Class c) {
        try {
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append("CREATE TABLE IF NOT EXISTS ");
            sBuilder.append(AnnotationUtil.getTableName(c));// 表名
//            String[] fields = ReflectUtil.getFields(c);
            Field[] fields = c.getDeclaredFields();
            String key = AnnotationUtil.getIdName(c);
            sBuilder.append(" ( ");
            List<Field> fieldList = new ArrayList<Field>();
            List<ModelProperty> modelProperties = ModelHolder.getProperties(c);
            for (ModelProperty modelProperty : modelProperties) {
                int modifiers = modelProperty.getField().getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isVolatile(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                } else {
                    fieldList.add(modelProperty.getField());
                }
            }
            for (int i = 0, l = fieldList.size(); i < l; i++) {
                Field field = fieldList.get(i);
                String fieldName = field.getName();
//                if (Modifier.FINAL == field.getModifiers()) break; //如果是final类型的，跳过
                if (fieldName.equals(key)) {
                    String type = getType(field);
                    if ("INTEGER".equals(type)) {
                        sBuilder.append(fieldName).append(" ").append(type).append(" PRIMARY KEY AUTOINCREMENT NOT NULL ");
                    } else {
                        sBuilder.append(fieldName).append(" ").append(type).append(" PRIMARY KEY NOT NULL ");
                    }
                } else {
                    sBuilder.append(fieldName).append(" ").append(getType(field));
                }
                if (i != l - 1) {
                    sBuilder.append(",");
                } else {
                    sBuilder.append(" );");
                }
            }
            Log.i("createTable: " + sBuilder.toString());
            database.execSQL(sBuilder.toString());
        } catch (Exception e) {
            Log.e(e.getMessage());
            return false;
        }
        return true;
    }


    /**
     * return rowid of current table.
     * <br>
     * 返回插入的自增字段的值
     *
     * @return the rowid of table
     */
    private int getLast_insert_rowid() {
        lock.readLock().lock();
        String sql = "select last_insert_rowid() newid;";
        Cursor cursor = null;
        int rowid = 0;
        try {
            cursor = database.rawQuery(sql, null);
            if (cursor != null && cursor.moveToNext()) {
                rowid = cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e("Exception", e);
        } finally {
            closeCursor(cursor);
            lock.readLock().unlock();
        }
        return rowid;
    }

    /**
     * convert model field to table field type.
     * <br>
     * 将模型字段类型对应到数据库字段上
     *
     * @param field field
     * @return the type of filed
     */
    private static String getType(Field field) {
        Class<?> type = field.getType();
        /**
         * 2014/4/9 lori.lin
         * 没有使用到该行代码
         */
//        field.getModifiers();
        String t = "TEXT";
        if (type == String.class) {
            t = "TEXT";
        } else if (type == int.class || type == long.class || type == short.class) {
            t = "INTEGER";
        } else if (type == float.class) {
            t = "REAL";
        } else if (type == double.class) {
            t = "REAL";
        }
        return t;
    }
}