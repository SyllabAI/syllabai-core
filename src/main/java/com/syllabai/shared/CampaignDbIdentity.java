package com.syllabai.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Campaign database identity (T-C04 r2 hardening, operator directive
 * 2026-09-13): every context boot — web app AND ingestion CLI runners —
 * prints and records WHERE it is running, so the campaign DB is never a
 * silent, anonymous target.
 *
 * <p>On boot this component:</p>
 * <ol>
 *   <li>logs {@code CAMPAIGN.DB.IDENTITY db=<name> host=<addr> label=<label>}
 *       — the explicit startup identity line;</li>
 *   <li>upserts the single {@code campaign_db_identity} row (V15) with the
 *       configured campaign label. The label defaults to {@code UNCLAIMED}:
 *       only a campaign run that passes
 *       {@code --syllabai.campaign.label=T-C04-CAMPAIGN} claims the database,
 *       and destructive tooling refuses to operate on a database whose row is
 *       missing or carries a different label — missing identity → fail
 *       closed.</li>
 * </ol>
 *
 * <p>Identity recording is deliberately non-fatal: if the table is absent
 * (e.g. Flyway disabled in a scratch environment) the boot proceeds with a
 * warning. The fail-closed enforcement lives where destruction is possible —
 * the repair/orchestration preflight — not in the identity recorder.</p>
 */
@Component
public class CampaignDbIdentity implements org.springframework.beans.factory.SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CampaignDbIdentity.class);

    private final JdbcTemplate jdbc;

    @Value("${syllabai.campaign.label:UNCLAIMED}")
    private String campaignLabel;

    @Value("${syllabai.campaign.commit:}")
    private String coreCommit;

    public CampaignDbIdentity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            String db = jdbc.queryForObject("SELECT current_database()", String.class);
            String host = jdbc.queryForObject(
                    "SELECT COALESCE(inet_server_addr()::text, 'unix-socket')", String.class);
            jdbc.update("""
                    INSERT INTO campaign_db_identity
                        (id, campaign_label, db_name, host_addr, core_commit, note)
                    VALUES (1, ?, ?, ?, ?, 'claimed at startup')
                    ON CONFLICT (id) DO UPDATE SET
                        campaign_label = EXCLUDED.campaign_label,
                        db_name = EXCLUDED.db_name,
                        host_addr = EXCLUDED.host_addr,
                        core_commit = EXCLUDED.core_commit,
                        last_seen_at = now()
                    """, campaignLabel, db, host, coreCommit.isBlank() ? null : coreCommit);
            log.info("CAMPAIGN.DB.IDENTITY db={} host={} label={} commit={}",
                    db, host, campaignLabel, coreCommit);
        } catch (DataAccessException e) {
            log.warn("CAMPAIGN.DB.IDENTITY could not be recorded: {}", e.getMessage());
        }
    }
}
