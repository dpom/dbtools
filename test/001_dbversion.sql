-- create dbversion table
DROP TABLE IF EXISTS dbversion;

CREATE TABLE dbversion
(
  dbversion_version integer NOT NULL,
  dbversion_date timestamp with time zone NOT NULL,
  dbversion_comments character varying(256),
  dbversion_last boolean NOT NULL,
  CONSTRAINT dbversion_pkey PRIMARY KEY (dbversion_version)
)
WITH (
  OIDS=FALSE
);
-- create-dbversion ends here
