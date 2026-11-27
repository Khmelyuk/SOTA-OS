import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver;
import sotaos.persistence.SqlDelightStore;
import sotaos.persistence.db.SotaOsDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.Properties;

/** Authoring-only probe: no file argument and no production database access. */
class CaptureCatalog {
    public static void main(String[] args) throws Exception {
        var driver = new JdbcSqliteDriver("jdbc:sqlite:", new Properties());
        try {
            SotaOsDatabase.Companion.getSchema().create(driver);
            dump(driver.getConnection(), "declared");
            new SqlDelightStore(driver);
            dump(driver.getConnection(), "runtime");
        } finally {
            driver.close();
        }
    }

    private static void dump(Connection connection, String stage) throws Exception {
        var query = "SELECT type,name,tbl_name,sql FROM sqlite_master "
            + "WHERE name NOT GLOB 'sqlite_*' ORDER BY type,name";
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                var sql = Base64.getEncoder().encodeToString(rows.getString(4).getBytes(StandardCharsets.UTF_8));
                System.out.println(stage + "\t" + rows.getString(1) + "\t" + rows.getString(2)
                    + "\t" + rows.getString(3) + "\t" + sql);
            }
        }
    }
}
