-- create users table
DROP TABLE IF EXISTS users;

CREATE TABLE users
(
  id character varying(75) NOT NULL,
  password character varying(75) NOT NULL,
  role character varying(75),
  surname character varying(75) NOT NULL,
  firstname character varying(75) NOT NULL,
  CONSTRAINT users_pkey PRIMARY KEY (id)
)
WITH (
OIDS=FALSE
);
-- create-users ends here
