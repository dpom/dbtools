-- create test1 table
DROP TABLE IF EXISTS test1;

CREATE TABLE test1
(
id character varying(75) NOT NULL,
comments character varying(75) NOT NULL,
CONSTRAINT test1_pkey PRIMARY KEY (id)
)
WITH (
OIDS=FALSE
);
-- create-test1 ends here
