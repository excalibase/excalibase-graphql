-- Fixture for relationship names per role: several tables pointing at one table, three keys from one
-- table to the same table, and a composite key beside a single-column key to the same table.

CREATE TABLE teams (
    id   BIGINT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE seasons (
    id     BIGINT PRIMARY KEY,
    league TEXT NOT NULL,
    year   INTEGER NOT NULL,
    UNIQUE (league, year)
);

CREATE TABLE matches (
    id                  BIGINT PRIMARY KEY,
    home_team_id        BIGINT NOT NULL REFERENCES teams(id),
    away_team_id        BIGINT NOT NULL REFERENCES teams(id),
    winner_team_id      BIGINT REFERENCES teams(id),
    league              TEXT NOT NULL,
    year                INTEGER NOT NULL,
    replay_of_season_id BIGINT REFERENCES seasons(id),
    FOREIGN KEY (league, year) REFERENCES seasons(league, year)
);

CREATE TABLE players (
    id      BIGINT PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    name    TEXT NOT NULL
);

CREATE TABLE transfers (
    id           BIGINT PRIMARY KEY,
    player_id    BIGINT NOT NULL REFERENCES players(id),
    from_team_id BIGINT NOT NULL REFERENCES teams(id),
    to_team_id   BIGINT NOT NULL REFERENCES teams(id)
);

INSERT INTO teams (id, name) VALUES (1, 'Lions'), (2, 'Tigers');
INSERT INTO seasons (id, league, year) VALUES (1, 'north', 2025), (2, 'north', 2026);
INSERT INTO matches (id, home_team_id, away_team_id, winner_team_id, league, year, replay_of_season_id) VALUES
    (1, 1, 2, 1, 'north', 2026, NULL),
    (2, 2, 1, NULL, 'north', 2026, 1);
INSERT INTO players (id, team_id, name) VALUES (1, 1, 'Ann'), (2, 2, 'Bo');
INSERT INTO transfers (id, player_id, from_team_id, to_team_id) VALUES (1, 1, 2, 1);
