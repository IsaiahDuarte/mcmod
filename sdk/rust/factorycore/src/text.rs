use core::fmt;

/// Host text limit in bytes (labels, IDs, keys, log lines, panic reports).
pub const MAX_TEXT: usize = 128;

/// `text` cut to at most [`MAX_TEXT`] bytes on a character boundary.
pub fn truncate(text: &str) -> &str {
    if text.len() <= MAX_TEXT {
        return text;
    }
    let mut end = MAX_TEXT;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// A stack buffer of [`MAX_TEXT`] bytes that formats without allocating, silently dropping
/// whatever does not fit (always on a character boundary).
pub struct Line {
    bytes: [u8; MAX_TEXT],
    len: usize,
}

impl Line {
    pub const fn new() -> Self {
        Line {
            bytes: [0; MAX_TEXT],
            len: 0,
        }
    }

    pub fn as_str(&self) -> &str {
        // Only whole UTF-8 characters are ever written.
        core::str::from_utf8(&self.bytes[..self.len]).unwrap_or("")
    }
}

impl fmt::Write for Line {
    fn write_str(&mut self, text: &str) -> fmt::Result {
        let room = MAX_TEXT - self.len;
        let mut end = text.len().min(room);
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        self.bytes[self.len..self.len + end].copy_from_slice(&text.as_bytes()[..end]);
        self.len += end;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::fmt::Write;

    #[test]
    fn formatting_truncates_on_character_boundaries() {
        let mut line = Line::new();
        write!(line, "count: {}", 42).unwrap();
        assert_eq!(line.as_str(), "count: 42");

        let mut line = Line::new();
        let long = "é".repeat(100); // 200 bytes
        write!(line, "{long}").unwrap();
        assert_eq!(line.as_str(), "é".repeat(64));
        write!(line, "more").unwrap();
        assert_eq!(line.as_str().len(), MAX_TEXT);
    }

    #[test]
    fn truncate_keeps_short_text_and_cuts_on_boundaries() {
        assert_eq!(truncate("short"), "short");
        let text = "a".repeat(127) + "é";
        assert_eq!(truncate(&text), "a".repeat(127));
    }
}
