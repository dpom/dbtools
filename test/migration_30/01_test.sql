DROP TABLE IF EXISTS test1;
-- create test2 table
DROP TABLE IF EXISTS test2;

CREATE TABLE test2
(
id character varying(75) NOT NULL,
comments character varying(75) NOT NULL,
CONSTRAINT test1_pkey PRIMARY KEY (id)
)
WITH (
OIDS=FALSE
);
-- create-test2 ends here
