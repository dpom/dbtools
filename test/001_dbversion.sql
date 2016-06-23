-- create dbversion table
DROP TABLE IF EXISTS dbversion;

CREATE TABLE dbversion
(
  version integer NOT NULL,
  install_date timestamp with time zone NOT NULL,
  comments character varying(256),
  CONSTRAINT dbversion_pkey PRIMARY KEY (version)
)
WITH (
  OIDS=FALSE
);
-- create-dbversion ends here
