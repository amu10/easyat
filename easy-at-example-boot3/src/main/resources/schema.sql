DROP TABLE IF EXISTS account;
CREATE TABLE account (
    id BIGINT PRIMARY KEY,
    balance INT NOT NULL
);
INSERT INTO account(id,balance) VALUES (1,1000);
INSERT INTO account(id,balance) VALUES (2,500);
