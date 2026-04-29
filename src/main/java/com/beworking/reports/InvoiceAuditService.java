package com.beworking.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InvoiceAuditService {

    private final JdbcTemplate jdbc;

    public InvoiceAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Builds the invoice audit report for the given month and cuentas.
     * Sections: status breakdown, outstanding, same-day duplicates,
     * cross-day same-amount, paid without Stripe ref, anomalies.
     */
    public Map<String, Object> buildReport(YearMonth month, List<String> cuentas) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        Object[] params = new Object[] { start, end };

        String cuentaInClause = buildInClause(cuentas);
        Object[] cuentaParams = cuentas.toArray();

        Map<String, Object> result = new HashMap<>();
        result.put("month", month.toString());
        result.put("cuentas", cuentas);
        result.put("statusBreakdown", statusBreakdown(start, end, cuentaInClause, cuentaParams));
        result.put("outstanding", outstanding(start, end, cuentaInClause, cuentaParams));
        result.put("sameDayDuplicates", sameDayDuplicates(start, end, cuentaInClause, cuentaParams));
        result.put("crossDayDuplicates", crossDayDuplicates(start, end, cuentaInClause, cuentaParams));
        result.put("paidWithoutStripe", paidWithoutStripe(start, end, cuentaInClause, cuentaParams));
        result.put("anomalies", anomalies(start, end, cuentaInClause, cuentaParams));
        return result;
    }

    private List<Map<String, Object>> statusBreakdown(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT COALESCE(estado, '<null>') AS estado, COUNT(*) AS count, COALESCE(SUM(total), 0) AS amount"
            + " FROM beworking.facturas"
            + " WHERE holdedcuenta " + cuentaIn
            + " AND creacionfecha >= ? AND creacionfecha < ?"
            + " GROUP BY estado ORDER BY 2 DESC";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private List<Map<String, Object>> outstanding(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT id, idfactura, idcliente, total, creacionfecha::date AS dia, fechacobro1, stripepaymentintentid1"
            + " FROM beworking.facturas"
            + " WHERE holdedcuenta " + cuentaIn
            + " AND creacionfecha >= ? AND creacionfecha < ?"
            + " AND estado = 'Pendiente'"
            + " ORDER BY creacionfecha";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private List<Map<String, Object>> sameDayDuplicates(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT f.idcliente, c.name AS client_name, f.total, f.creacionfecha::date AS dia,"
            + "        COUNT(*) AS dupes, ARRAY_AGG(f.idfactura ORDER BY f.id) AS facturas,"
            + "        ARRAY_AGG(f.id ORDER BY f.id) AS ids"
            + " FROM beworking.facturas f"
            + " LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente"
            + " WHERE f.holdedcuenta " + cuentaIn
            + " AND f.creacionfecha >= ? AND f.creacionfecha < ?"
            + " GROUP BY f.idcliente, c.name, f.total, f.creacionfecha::date"
            + " HAVING COUNT(*) > 1"
            + " ORDER BY dupes DESC, f.total DESC";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private List<Map<String, Object>> crossDayDuplicates(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT f.idcliente, c.name AS client_name, f.total,"
            + "        COUNT(*) AS dupes, ARRAY_AGG(f.idfactura ORDER BY f.id) AS facturas,"
            + "        ARRAY_AGG(f.creacionfecha::date ORDER BY f.id) AS dates,"
            + "        ARRAY_AGG(f.id ORDER BY f.id) AS ids"
            + " FROM beworking.facturas f"
            + " LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente"
            + " WHERE f.holdedcuenta " + cuentaIn
            + " AND f.creacionfecha >= ? AND f.creacionfecha < ?"
            + " GROUP BY f.idcliente, c.name, f.total"
            + " HAVING COUNT(*) > 1 AND COUNT(DISTINCT f.creacionfecha::date) > 1"
            + " ORDER BY dupes DESC, f.total DESC";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private List<Map<String, Object>> paidWithoutStripe(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT f.id, f.idfactura, f.idcliente, c.name AS client_name, f.total,"
            + "        f.creacionfecha::date AS dia, f.fechacobro1"
            + " FROM beworking.facturas f"
            + " LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente"
            + " WHERE f.holdedcuenta " + cuentaIn
            + " AND f.creacionfecha >= ? AND f.creacionfecha < ?"
            + " AND f.estado = 'Pagado'"
            + " AND (f.stripepaymentintentid1 IS NULL OR f.stripepaymentintentid1 = '')"
            + " ORDER BY f.creacionfecha";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private List<Map<String, Object>> anomalies(LocalDate start, LocalDate end, String cuentaIn, Object[] cuentaParams) {
        String sql = "SELECT f.id, f.idfactura, f.idcliente, c.name AS client_name, f.total, f.estado,"
            + "        f.descripcion, f.creacionfecha::date AS dia"
            + " FROM beworking.facturas f"
            + " LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente"
            + " WHERE f.holdedcuenta " + cuentaIn
            + " AND f.creacionfecha >= ? AND f.creacionfecha < ?"
            + " AND (f.total IS NULL OR f.total <= 0 OR f.total > 5000 OR f.idcliente IS NULL)"
            + " ORDER BY f.total DESC NULLS FIRST";
        return jdbc.queryForList(sql, append(cuentaParams, start, end));
    }

    private static String buildInClause(List<String> cuentas) {
        StringBuilder sb = new StringBuilder("IN (");
        for (int i = 0; i < cuentas.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }

    private static Object[] append(Object[] base, Object... extra) {
        Object[] combined = new Object[base.length + extra.length];
        System.arraycopy(base, 0, combined, 0, base.length);
        System.arraycopy(extra, 0, combined, base.length, extra.length);
        return combined;
    }
}
