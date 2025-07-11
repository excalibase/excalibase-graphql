-- Sample database initialization script for Excalibase GraphQL Demo
-- This script creates sample tables and data for testing the GraphQL API

-- Create schema if not exists
CREATE SCHEMA IF NOT EXISTS hana;

-- Set search path to the hana schema
SET search_path TO hana;

-- Sample users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample posts table
CREATE TABLE IF NOT EXISTS posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    published BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample comments table
CREATE TABLE IF NOT EXISTS comments (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE,
    author_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert sample data
INSERT INTO users (username, email, first_name, last_name) VALUES
    ('john_doe', 'john@example.com', 'John', 'Doe'),
    ('jane_smith', 'jane@example.com', 'Jane', 'Smith'),
    ('bob_wilson', 'bob@example.com', 'Bob', 'Wilson')
ON CONFLICT (username) DO NOTHING;

INSERT INTO posts (title, content, author_id, published) VALUES
    ('Introduction to GraphQL', 'GraphQL is a query language for APIs...', 1, true),
    ('Getting Started with Docker', 'Docker is a containerization platform...', 2, true),
    ('Spring Boot Best Practices', 'Here are some best practices for Spring Boot...', 1, false),
    ('Database Design Patterns', 'When designing databases, consider these patterns...', 3, true)
ON CONFLICT DO NOTHING;

INSERT INTO comments (content, post_id, author_id) VALUES
    ('Great introduction!', 1, 2),
    ('Very helpful, thanks!', 1, 3),
    ('Looking forward to more posts like this.', 2, 1),
    ('Could you elaborate on the security aspects?', 4, 2)
ON CONFLICT DO NOTHING;

-- Grant permissions to the application user
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA hana TO hana001;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA hana TO hana001;

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_posts_author_id ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_comments_post_id ON comments(post_id);
CREATE INDEX IF NOT EXISTS idx_comments_author_id ON comments(author_id);

COMMIT; 