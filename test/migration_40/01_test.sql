-- create test3 table
DROP TABLE IF EXISTS test3;

CREATE TABLE test3
(
id character varying(75) NOT NULL,
comments character varying(75) NOT NULL,
CONSTRAINT test3_pkey PRIMARY KEY (id)
)
WITH (
OIDS=FALSE
);
-- create-test3 ends here
