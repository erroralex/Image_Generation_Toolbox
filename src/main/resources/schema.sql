-- Table for indexed images
CREATE TABLE IF NOT EXISTS images
(
    id
    INTEGER
    PRIMARY
    KEY
    AUTOINCREMENT,
    file_path
    TEXT
    UNIQUE
    NOT
    NULL,
    is_starred
    BOOLEAN
    DEFAULT
    0,
    last_scanned
    INTEGER
);

-- Table for parsed metadata (Key-Value pairs per image)
CREATE TABLE IF NOT EXISTS image_metadata
(
    image_id
    INTEGER,
    key
    TEXT,
    value
    TEXT,
    FOREIGN
    KEY
(
    image_id
) REFERENCES images
(
    id
)
    );

-- Table for tags
CREATE TABLE IF NOT EXISTS image_tags
(
    image_id
    INTEGER,
    tag
    TEXT,
    FOREIGN
    KEY
(
    image_id
) REFERENCES images
(
    id
)
    );