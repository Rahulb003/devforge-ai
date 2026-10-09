-- Line-level review: a comment may be anchored to a line of a changed file. Both null for a comment
-- on the pull request as a whole. The line is the file's line number on the source side - the
-- version under review.
ALTER TABLE pull_request_comments ADD COLUMN path VARCHAR(1024);
ALTER TABLE pull_request_comments ADD COLUMN line INT;
