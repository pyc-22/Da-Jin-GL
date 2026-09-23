"""Patch existing Nginx configs without replacing domains, certificates or WS rules."""
import re
import sys
from pathlib import Path

HEALTH = '''
  location = /actuator/health {
    proxy_pass http://backend:8080/actuator/health;
    proxy_set_header Host $host;
  }
'''

def patch(text):
    # Deployment configs use ordinary server blocks; strings/comments are skipped by the lexer.
    token = re.compile(r'#[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]|\bserver\s*(?=\{)')
    blocks, stack, pending = [], [], False
    for m in token.finditer(text):
        value = m.group()
        if value.startswith(('#', '"', "'")): continue
        if value.strip() == 'server': pending = True
        elif value == '{': stack.append((m.end(), pending)); pending = False
        elif value == '}':
            if not stack: raise ValueError('Unbalanced Nginx braces')
            start, server = stack.pop()
            if server: blocks.append((start, m.start()))
    if stack: raise ValueError('Unbalanced Nginx braces')
    if not blocks: raise ValueError('No server blocks found')
    for start, end in reversed(blocks):
        body = text[start:end]
        body = re.sub(r'\bclient_max_body_size\s+[^;]+;', 'client_max_body_size 10m;', body)
        if 'client_max_body_size' not in body: body = '\n  client_max_body_size 10m;' + body
        if not re.search(r'\blocation\s*=\s*/actuator/health\s*\{', body): body = HEALTH + body
        text = text[:start] + body + text[end:]
    return text

if __name__ == '__main__':
    for name in sys.argv[1:]:
        p = Path(name)
        original = p.read_text(encoding='utf-8')
        updated = patch(original)
        # Keep an existing bind-mounted file's inode.
        p.write_text(updated, encoding='utf-8')
        print(f'Updated Nginx config: {p.name}')
