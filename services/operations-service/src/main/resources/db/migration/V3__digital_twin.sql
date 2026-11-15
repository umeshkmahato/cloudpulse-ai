-- Relational digital twin (no graph DB needed at this size: a few dozen nodes, recursive walks done in code).
-- deployment_status keeps the twin HONEST: PLANNED components are part of the target architecture but are not deployed.
CREATE TABLE twin_components (
    id                VARCHAR(64) PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    kind              VARCHAR(20)  NOT NULL,   -- CLIENT APPLICATION SERVICE DEPLOYMENT DATABASE TOPIC CLOUD_RESOURCE SECURITY_POLICY
    description       VARCHAR(300) NOT NULL,
    deployment_status VARCHAR(16)  NOT NULL,   -- ACTIVE_LOCAL | PLANNED
    criticality       VARCHAR(10)  NOT NULL,
    parent_id         VARCHAR(64),
    ui_x              INT NOT NULL DEFAULT 0,
    ui_y              INT NOT NULL DEFAULT 0
);

-- relation NEEDS: "from_id cannot fully work without to_id". mode SYNC = request path, ASYNC = buffered via a topic.
-- relation PROTECTS: policy -> protected component (informational, ignored by impact analysis).
CREATE TABLE twin_dependencies (
    from_id     VARCHAR(64) NOT NULL REFERENCES twin_components (id),
    to_id       VARCHAR(64) NOT NULL REFERENCES twin_components (id),
    relation    VARCHAR(10) NOT NULL DEFAULT 'NEEDS',
    mode        VARCHAR(5)  NOT NULL,
    description VARCHAR(200),
    PRIMARY KEY (from_id, to_id, relation)
);

CREATE TABLE twin_transactions (
    id                VARCHAR(64) PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    business_impact   VARCHAR(300) NOT NULL,
    revenue_impacting BOOLEAN NOT NULL
);

CREATE TABLE twin_transaction_components (
    transaction_id VARCHAR(64) NOT NULL REFERENCES twin_transactions (id),
    component_id   VARCHAR(64) NOT NULL REFERENCES twin_components (id),
    PRIMARY KEY (transaction_id, component_id)
);

CREATE TABLE twin_recovery_steps (
    component_id VARCHAR(64) NOT NULL REFERENCES twin_components (id),
    kind         VARCHAR(16) NOT NULL,         -- MESSAGE_RECOVERY | RECOVERY | MITIGATION
    step_no      INT NOT NULL,
    step_text    VARCHAR(400) NOT NULL,
    PRIMARY KEY (component_id, kind, step_no)
);

INSERT INTO twin_components (id, name, kind, description, deployment_status, criticality, parent_id, ui_x, ui_y) VALUES
 ('customer',            'Customer / UI',      'CLIENT',      'Browser or API client placing orders',                       'ACTIVE_LOCAL', 'high',   NULL,       70, 200),
 ('cloudpulse',          'CloudPulse platform','APPLICATION', 'The business application (order, inventory, operations)',    'ACTIVE_LOCAL', 'high',   NULL,       70,  40),
 ('order-service',       'order-service',      'SERVICE',     'Accepts orders, persists them, publishes order.created',     'ACTIVE_LOCAL', 'high',   'cloudpulse', 250, 200),
 ('inventory-service',   'inventory-service',  'SERVICE',     'Reserves stock idempotently, publishes inventory.updated',   'ACTIVE_LOCAL', 'high',   'cloudpulse', 610, 110),
 ('operations-service',  'operations-service', 'SERVICE',     'Telemetry, anomaly detection, incidents, digital twin',      'ACTIVE_LOCAL', 'medium', 'cloudpulse', 250, 400),
 ('analytics-sink',      'analytics-sink',     'SERVICE',     'Streams events into the analytics store',                    'ACTIVE_LOCAL', 'low',    'cloudpulse', 610, 290),
 ('orders-created',      'orders-created',     'TOPIC',       'Pub/Sub topic: order.created events',                        'ACTIVE_LOCAL', 'high',   NULL,       430, 110),
 ('inventory-updated',   'inventory-updated',  'TOPIC',       'Pub/Sub topic: inventory.updated events',                    'ACTIVE_LOCAL', 'high',   NULL,       430, 290),
 ('application-metrics', 'application-metrics','TOPIC',       'Pub/Sub topic: metric.sample events',                        'ACTIVE_LOCAL', 'medium', NULL,       430, 400),
 ('postgres',            'PostgreSQL',         'DATABASE',    'System of record (schemas orders, inventory, operations)',   'ACTIVE_LOCAL', 'high',   NULL,       790, 200),
 ('analytics-store',     'analytics store',    'DATABASE',    'Analytics schema (BigQuery stand-in)',                       'ACTIVE_LOCAL', 'low',    NULL,       790, 345),
 ('gke-cluster',         'GKE cluster',        'CLOUD_RESOURCE','Target runtime (not deployed; billable, needs approval)', 'PLANNED',      'high',   NULL,       250, 520),
 ('deploy-order-service','deploy/order-service','DEPLOYMENT',  'Kubernetes deployment (HPA, probes, PDB) - planned',         'PLANNED',      'high',   NULL,       70, 330),
 ('deploy-inventory-service','deploy/inventory-service','DEPLOYMENT','Kubernetes deployment - planned',                      'PLANNED',      'high',   NULL,       610, 20),
 ('netpol-default-deny', 'NetworkPolicy default-deny','SECURITY_POLICY','Default-deny ingress/egress with explicit allows - planned','PLANNED','high',NULL,       70, 460);

INSERT INTO twin_dependencies (from_id, to_id, relation, mode, description) VALUES
 ('customer',            'order-service',       'NEEDS', 'SYNC',  'POST /api/v1/orders'),
 ('order-service',       'postgres',            'NEEDS', 'SYNC',  'persists orders + outbox'),
 ('order-service',       'inventory-updated',   'NEEDS', 'ASYNC', 'order status updates'),
 ('orders-created',      'order-service',       'NEEDS', 'ASYNC', 'producer via outbox'),
 ('inventory-service',   'orders-created',      'NEEDS', 'ASYNC', 'consumes orders'),
 ('inventory-service',   'postgres',            'NEEDS', 'SYNC',  'stock + dedupe + outbox'),
 ('inventory-updated',   'inventory-service',   'NEEDS', 'ASYNC', 'producer via outbox'),
 ('analytics-sink',      'orders-created',      'NEEDS', 'ASYNC', 'analytics subscription'),
 ('analytics-sink',      'inventory-updated',   'NEEDS', 'ASYNC', 'analytics subscription'),
 ('analytics-sink',      'application-metrics', 'NEEDS', 'ASYNC', 'analytics subscription'),
 ('analytics-sink',      'analytics-store',     'NEEDS', 'SYNC',  'writes events'),
 ('analytics-store',     'postgres',            'NEEDS', 'SYNC',  'same instance locally'),
 ('operations-service',  'application-metrics', 'NEEDS', 'ASYNC', 'telemetry ingest'),
 ('operations-service',  'analytics-store',     'NEEDS', 'SYNC',  'analytics queries'),
 ('operations-service',  'postgres',            'NEEDS', 'SYNC',  'samples + incidents'),
 ('application-metrics', 'operations-service',  'NEEDS', 'ASYNC', 'ingest API publishes'),
 ('order-service',       'deploy-order-service','NEEDS', 'SYNC',  'runs on'),
 ('inventory-service',   'deploy-inventory-service','NEEDS','SYNC','runs on'),
 ('deploy-order-service','gke-cluster',         'NEEDS', 'SYNC',  'scheduled on'),
 ('deploy-inventory-service','gke-cluster',     'NEEDS', 'SYNC',  'scheduled on'),
 ('netpol-default-deny', 'order-service',       'PROTECTS','SYNC','network isolation'),
 ('netpol-default-deny', 'inventory-service',   'PROTECTS','SYNC','network isolation');

INSERT INTO twin_transactions (id, name, business_impact, revenue_impacting) VALUES
 ('order-intake',        'Order intake',               'No new orders can be accepted: direct revenue loss.',                        TRUE),
 ('stock-reservation',   'Stock reservation',          'Orders are accepted but stock is not reserved: fulfilment is delayed.',      TRUE),
 ('order-confirmation',  'Order confirmation',         'Customers see PENDING orders longer; confirmations delayed, not lost.',      TRUE),
 ('analytics-ingest',    'Analytics ingestion',        'Dashboards and reports go stale; no effect on customers.',                   FALSE),
 ('telemetry-detection', 'Telemetry and incident detection','Blind spot: no anomaly detection or remediation; customers unaffected.', FALSE);

INSERT INTO twin_transaction_components (transaction_id, component_id) VALUES
 ('order-intake','order-service'),('order-intake','postgres'),
 ('stock-reservation','inventory-service'),('stock-reservation','orders-created'),('stock-reservation','postgres'),
 ('order-confirmation','order-service'),('order-confirmation','inventory-updated'),('order-confirmation','inventory-service'),
 ('analytics-ingest','analytics-sink'),('analytics-ingest','analytics-store'),('analytics-ingest','orders-created'),('analytics-ingest','inventory-updated'),
 ('telemetry-detection','operations-service'),('telemetry-detection','application-metrics');

INSERT INTO twin_recovery_steps (component_id, kind, step_no, step_text) VALUES
 ('inventory-service','MITIGATION',1,'Orders keep being accepted: order-service only needs Postgres and Pub/Sub.'),
 ('inventory-service','MESSAGE_RECOVERY',1,'orders-created messages queue in orders-created-inventory-sub (7-day retention) and drain when the service returns.'),
 ('inventory-service','MESSAGE_RECOVERY',2,'Consumption is idempotent (eventId + orderId), so redelivery cannot double-reserve stock.'),
 ('inventory-service','MESSAGE_RECOVERY',3,'Check the orders-created DLQ and replay with scripts/dlq-replay.sh once the cause is fixed.'),
 ('inventory-service','RECOVERY',1,'Restart or scale the service (stateless); verify /actuator/health and the subscription backlog draining.'),
 ('order-service','RECOVERY',1,'Restart or scale out (stateless). Events committed to the outbox publish after recovery; nothing committed is lost.'),
 ('order-service','MITIGATION',1,'Fail traffic over to healthy replicas / another zone; shed load at the gateway if saturated.'),
 ('postgres','RECOVERY',1,'Fail over to the HA replica (Cloud SQL REGIONAL, planned) or restore from point-in-time backup.'),
 ('postgres','MESSAGE_RECOVERY',1,'Producers keep events in the outbox; Pub/Sub retains messages while consumers retry and dead-letter.'),
 ('analytics-sink','MESSAGE_RECOVERY',1,'Pub/Sub retains events; the sink resumes and drains the backlog (idempotent on event_id).'),
 ('operations-service','MESSAGE_RECOVERY',1,'Metrics queue in application-metrics; restart and the backlog is ingested.'),
 ('orders-created','MESSAGE_RECOVERY',1,'Producer outbox keeps unpublished events; the relay publishes once the topic is reachable.'),
 ('inventory-updated','MESSAGE_RECOVERY',1,'Producer outbox keeps unpublished events; subscribers receive them after recovery.');
