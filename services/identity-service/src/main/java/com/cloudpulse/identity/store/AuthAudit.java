package com.cloudpulse.identity.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only security audit (also logged as structured JSON). Failures to write never block authentication decisions. */
@Repository
public class AuthAudit {
    private static final Logger log = LoggerFactory.getLogger("security-audit");
    private final JdbcTemplate jdbc;

    public AuthAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String event, String username, String ip, String detail) {
        log.info("auth event={} user={} ip={} detail={}", event, username, ip, detail);
        try {
            jdbc.update("insert into identity.auth_audit (at, event, username, source_ip, detail) values (?,?,?,?,?)", Timestamp.from(Instant.now()), event, username, ip,
                    detail == null ? null : detail.substring(0, Math.min(detail.length(), 300)));
        } catch (Exception e) {
            log.warn("audit write failed: {}", e.toString());
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("select id, at, event, username, source_ip, detail from identity.auth_audit order by id desc limit ?", limit);
    }
}
