package org.easymapper.mapper;

import org.apache.commons.beanutils.BeanUtils;

import javax.persistence.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EntityHelper {
    /**
     * 实体类 => 表对象
     */
    private static final Map<Class<?>, EntityTable> entityTableMap = new HashMap<Class<?>, EntityTable>();

    /**
     * 获取表对象
     *
     * @param entityClass
     * @return
     */
    public static EntityTable getEntityTable(Class<?> entityClass) {
        EntityTable entityTable = entityTableMap.get(entityClass);
        if (entityTable == null) {
            initEntityNameMap(entityClass);
            entityTable = entityTableMap.get(entityClass);
        }
        if (entityTable == null) {
            throw new RuntimeException("无法获取实体类" + entityClass.getCanonicalName() + "对应的表名!");
        }
        return entityTable;
    }

    /**
     * 获取默认的orderby语句
     *
     * @param entityClass
     * @return
     */
    public static String getOrderByClause(Class<?> entityClass) {
        EntityTable table = getEntityTable(entityClass);
        if (table.getOrderByClause() != null) {
            return table.getOrderByClause();
        }
        StringBuilder orderBy = new StringBuilder();
        for (EntityColumn column : table.getEntityClassColumns()) {
            if (column.getOrderBy() != null) {
                if (orderBy.length() != 0) {
                    orderBy.append(",");
                }
                orderBy.append(column.getColumn()).append(" ").append(column.getOrderBy());
            }
        }
        table.setOrderByClause(orderBy.toString());
        return table.getOrderByClause();
    }

    /**
     * 获取全部列
     *
     * @param entityClass
     * @return
     */
    public static Set<EntityColumn> getColumns(Class<?> entityClass) {
        return getEntityTable(entityClass).getEntityClassColumns();
    }

    /**
     * 获取主键信息
     *
     * @param entityClass
     * @return
     */
    public static Set<EntityColumn> getPKColumns(Class<?> entityClass) {
        return getEntityTable(entityClass).getEntityClassPKColumns();
    }

    /**
     * 获取字段映射关系
     *
     * @param entityClass
     * @return
     */
    public static Map<String, String> getColumnAlias(Class<?> entityClass) {
        EntityTable entityTable = getEntityTable(entityClass);
        if (entityTable.getAliasMap() != null) {
            return entityTable.getAliasMap();
        }
        Set<EntityColumn> columnList = entityTable.getEntityClassColumns();
        entityTable.setAliasMap(new HashMap<>(columnList.size()));
        for (EntityColumn column : columnList) {
            entityTable.getAliasMap().put(column.getColumn(), column.getProperty());
        }
        return entityTable.getAliasMap();
    }

    /**
     * 获取查询的Select
     *
     * @param entityClass
     * @return
     */
    public static String getSelectColumns(Class<?> entityClass) {
        Set<EntityColumn> columnList = getColumns(entityClass);
        StringBuilder selectBuilder = new StringBuilder();
        boolean skipAlias = Map.class.isAssignableFrom(entityClass);
        for (EntityColumn entityColumn : columnList) {
            selectBuilder.append(entityColumn.getColumn());
            if (!skipAlias && !entityColumn.getColumn().equalsIgnoreCase(entityColumn.getProperty())) {
                selectBuilder.append(" ").append(entityColumn.getProperty().toUpperCase()).append(",");
            } else {
                selectBuilder.append(",");
            }
        }
        return selectBuilder.substring(0, selectBuilder.length() - 1);
    }

    /**
     * 获取查询的Select
     *
     * @param entityClass
     * @return
     */
    public static String getAllColumns(Class<?> entityClass) {
        EntityTable entityTable = getEntityTable(entityClass);
        Set<EntityColumn> columnList = entityTable.getEntityClassColumns();
        StringJoiner stringJoiner = new StringJoiner(", ");
        columnList.forEach(column -> stringJoiner.add(entityTable.getName() + "." + column.getColumn()));
        return stringJoiner.toString();
    }

    /**
     * 获取主键的Where语句
     *
     * @param entityClass
     * @return
     */
    public static String getPrimaryKeyWhere(Class<?> entityClass) {
        Set<EntityColumn> entityColumns = getPKColumns(entityClass);
        StringBuilder whereBuilder = new StringBuilder();
        for (EntityColumn column : entityColumns) {
            whereBuilder.append(column.getColumn()).append(" = ?").append(" AND ");
        }
        return whereBuilder.substring(0, whereBuilder.length() - 4);
    }

    protected static EntityColumn getColumnFromField(EntityTable entityTable, Field field) {
        EntityColumn entityColumn = new EntityColumn(entityTable);
        if (field.isAnnotationPresent(Id.class)) {
            entityColumn.setId(true);
        }
        String columnName = null;
        if (field.isAnnotationPresent(Column.class)) {
            Column column = field.getAnnotation(Column.class);
            columnName = column.name();
        }
        if (columnName == null || columnName.equals("")) {
            columnName = camelhumpToUnderline(field.getName());
        }
        entityColumn.setProperty(field.getName());
        entityColumn.setColumn(columnName.toUpperCase());
        entityColumn.setJavaType(field.getType());
        //order by
        if (field.isAnnotationPresent(OrderBy.class)) {
            OrderBy orderBy = field.getAnnotation(OrderBy.class);
            if (orderBy.value().equals("")) {
                entityColumn.setOrderBy("ASC");
            } else {
                entityColumn.setOrderBy(orderBy.value());
            }
        }
        //主键策略 - Oracle序列，MySql自动增长，UUID
        if (field.isAnnotationPresent(SequenceGenerator.class)) {
            SequenceGenerator sequenceGenerator = field.getAnnotation(SequenceGenerator.class);
            if (sequenceGenerator.sequenceName().equals("")) {
                throw new RuntimeException(entityTable.getClass() + "字段" + field.getName() + "的注解@SequenceGenerator未指定sequenceName!");
            }
            entityColumn.setSequenceName(sequenceGenerator.sequenceName());
        } else if (field.isAnnotationPresent(GeneratedValue.class)) {
            GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
            if (generatedValue.generator().equals("UUID")) {
                entityColumn.setUuid(true);
            } else if (generatedValue.generator().equals("JDBC")) {
                entityColumn.setIdentity(true);
                entityColumn.setGenerator("JDBC");
                entityTable.setKeyProperties(entityColumn.getProperty());
                entityTable.setKeyColumns(entityColumn.getColumn());
            } else {
                //允许通过generator来设置获取id的sql,例如mysql=CALL IDENTITY(),hsqldb=SELECT SCOPE_IDENTITY()
                //允许通过拦截器参数设置公共的generator
                if (generatedValue.strategy() == GenerationType.IDENTITY) {
                    //mysql的自动增长
                    entityColumn.setIdentity(true);
                    if (!generatedValue.generator().equals("")) {
                        entityColumn.setGenerator("SELECT LAST_INSERT_ID()");
                    }
                } else {
                    throw new RuntimeException(field.getName()
                            + " - 该字段@GeneratedValue配置只允许以下几种形式:" +
                            "\n1.全部数据库通用的@GeneratedValue(generator=\"UUID\")" +
                            "\n2.useGeneratedKeys的@GeneratedValue(generator=\\\"JDBC\\\")  " +
                            "\n3.类似mysql数据库的@GeneratedValue(strategy=GenerationType.IDENTITY[,generator=\"Mysql\"])");
                }
            }
        }
        return entityColumn;
    }

    /**
     * 初始化实体属性
     *
     * @param entityClass
     */
    public static synchronized void initEntityNameMap(Class<?> entityClass) {
        if (entityTableMap.get(entityClass) != null) {
            return;
        }
        //表名
        EntityTable entityTable = null;
        if (entityClass.isAnnotationPresent(Table.class)) {
            Table table = entityClass.getAnnotation(Table.class);
            if (!table.name().equals("")) {
                entityTable = new EntityTable();
                entityTable.setTable(table);
            }
        }
        if (entityTable == null) {
            entityTable = new EntityTable();
            //对大小写敏感的情况，这里不自动转换大小写，如果有需要，通过@Table注解实现
            entityTable.setName(camelhumpToUnderline(entityClass.getSimpleName()));
        }
        //列
        List<Field> fieldList = getAllField(entityClass, null);
        Set<EntityColumn> columnSet = new LinkedHashSet<EntityColumn>();
        Set<EntityColumn> pkColumnSet = new LinkedHashSet<EntityColumn>();
        for (Field field : fieldList) {
            //排除字段
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            EntityColumn entityColumn = getColumnFromField(entityTable, field);
            columnSet.add(entityColumn);
            if (entityColumn.isId()) {
                pkColumnSet.add(entityColumn);
            }
        }
        entityTable.setEntityClassColumns(columnSet);
        if (pkColumnSet.isEmpty()) {
            for (Field field : fieldList) {
                if (!field.isAnnotationPresent(Transient.class) && field.getName().equalsIgnoreCase("id")) {
                    pkColumnSet.add(getColumnFromField(entityTable, field));
                }
            }
        }
        entityTable.setEntityClassPKColumns(pkColumnSet);
        //缓存
        entityTableMap.put(entityClass, entityTable);
    }

    /**
     * 将驼峰风格替换为下划线风格
     */
    public static String camelhumpToUnderline(String str) {
        final int size;
        final char[] chars;
        final StringBuilder sb = new StringBuilder(
                (size = (chars = str.toCharArray()).length) * 3 / 2 + 1);
        char c;
        for (int i = 0; i < size; i++) {
            c = chars[i];
            if (isUppercaseAlpha(c)) {
                sb.append('_').append(c);
            } else {
                sb.append(toUpperAscii(c));
            }
        }
        return sb.charAt(0) == '_' ? sb.substring(1) : sb.toString();
    }

    /**
     * 将下划线风格替换为驼峰风格
     */
    public static String underlineToCamelhump(String str) {
        Matcher matcher = Pattern.compile("_[a-z]").matcher(str);
        StringBuilder builder = new StringBuilder(str);
        for (int i = 0; matcher.find(); i++) {
            builder.replace(matcher.start() - i, matcher.end() - i, matcher.group().substring(1).toUpperCase());
        }
        if (Character.isUpperCase(builder.charAt(0))) {
            builder.replace(0, 1, String.valueOf(Character.toLowerCase(builder.charAt(0))));
        }
        return builder.toString();
    }

    public static boolean isUppercaseAlpha(char c) {
        return (c >= 'A') && (c <= 'Z');
    }

    public static char toUpperAscii(char c) {
        if (isUppercaseAlpha(c)) {
            c -= (char) 0x20;
        }
        return c;
    }

    /**
     * 获取全部的Field
     *
     * @param entityClass
     * @param fieldList
     * @return
     */
    private static List<Field> getAllField(Class<?> entityClass, List<Field> fieldList) {
        if (fieldList == null) {
            fieldList = new ArrayList<Field>();
        }
        if (entityClass.equals(Object.class)) {
            return fieldList;
        }
        Field[] fields = entityClass.getDeclaredFields();
        for (Field field : fields) {
            //排除静态字段，解决bug#2
            if (!Modifier.isStatic(field.getModifiers())) {
                fieldList.add(field);
            }
        }
        Class<?> superClass = entityClass.getSuperclass();
        if (superClass != null
                && !superClass.equals(Object.class)
                && (superClass.isAnnotationPresent(Entity.class)
                || (!Map.class.isAssignableFrom(superClass)
                && !Collection.class.isAssignableFrom(superClass)))) {
            return getAllField(entityClass.getSuperclass(), fieldList);
        }
        return fieldList;
    }

    /**
     * map转换为Map
     *
     * @param map
     * @param beanClass
     * @return
     */
    public static Map<String, Object> map2AliasMap(Map<String, Object> map, Class<?> beanClass) {
        if (map == null) {
            return null;
        }
        Map<String, String> alias = getColumnAlias(beanClass);
        Map<String, Object> result = new HashMap<String, Object>();
        for (String name : map.keySet()) {
            String alia = name;
            //sql在被其他拦截器处理后，字段可能发生变化，例如分页插件增加rownum
            if (alias.containsKey(name)) {
                alia = alias.get(name);
            }
            result.put(alia, map.get(name));
        }
        return result;
    }

    /**
     * map转换为bean
     *
     * @param map
     * @param beanClass
     * @return
     */
    public static Object map2Bean(Map<String, Object> map, Class<?> beanClass) {
        try {
            if (map == null) {
                return null;
            }
            Map<String, Object> aliasMap = map2AliasMap(map, beanClass);
            Object bean = beanClass.newInstance();
            BeanUtils.copyProperties(bean, aliasMap);
            return bean;
        } catch (InstantiationException e) {
            throw new RuntimeException(beanClass.getCanonicalName() + "类没有默认空的构造方法!");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * mapList转换为beanList
     *
     * @param mapList
     * @param beanClass
     * @return
     */
    public static List<?> maplist2BeanList(List<?> mapList, Class<?> beanClass) {
        if (mapList == null || mapList.size() == 0) {
            return null;
        }
        List list = new ArrayList<Object>(mapList.size());
        for (Object map : mapList) {
            list.add(map2Bean((Map) map, beanClass));
        }
        mapList.clear();
        mapList.addAll(list);
        return mapList;
    }
}