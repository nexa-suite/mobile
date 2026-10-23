#!/usr/bin/env python3
"""Check Buyer client import boundaries against the canonical context vocabulary."""
from pathlib import Path
import posixpath
import re

ROOT = Path(__file__).resolve().parents[1]
CONTEXTS = frozenset((
    'tenant_access_governance', 'customer_buyer_relationships',
    'catalog_commercial_policy', 'sales_commitment', 'inventory_availability',
    'fulfillment_delivery', 'credit_receivables', 'payments',
    'business_documents', 'notifications', 'business_traceability',
))
LAYERS = frozenset(('domain', 'application', 'infrastructure', 'presentation'))
PACKAGE = 'package:nexa_buyer_mobile/'
IMPLEMENTATION_PACKAGES = (
    'package:http/', 'package:path_provider/', 'package:flutter_stripe/',
)
UI_PACKAGES = ('package:flutter/', 'package:provider/', 'package:go_router/')
TOKENS = re.compile(
    r'''r?(?:"{3}[\s\S]*?"{3}|'{3}[\s\S]*?'{3}|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')'''
    r'''|/\*[\s\S]*?\*/|//[^\n]*|\b(?:import|export|part)\b|;'''
)


def imports(source):
    """Read URI literals in directives, ignoring comments and ordinary strings."""
    directive = False
    for match in TOKENS.finditer(source):
        token = match.group()
        if token.startswith(('//', '/*')):
            continue
        if token in ('import', 'export', 'part'):
            directive = True
        elif token == ';':
            directive = False
        elif directive:
            literal = token[1:] if token.startswith('r') else token
            quote = 3 if literal.startswith(('"""', "'''")) else 1
            yield literal[quote:-quote]


def local_path(owner, uri):
    if uri.startswith(PACKAGE):
        return 'lib/' + uri[len(PACKAGE):]
    if ':' in uri:
        return None
    return posixpath.normpath(posixpath.join(posixpath.dirname(owner), uri))


def context_layer(path):
    pieces = path.split('/')
    if pieces[:2] == ['lib', 'contexts'] and len(pieces) >= 5:
        return pieces[2], pieces[3]
    return None, None


def inspect_sources(sources):
    errors = []
    for path, source in sorted(sources.items()):
        context, layer = context_layer(path)
        if path.startswith('lib/contexts/'):
            if context not in CONTEXTS or layer not in LAYERS:
                errors.append(f'{path}: unknown context or layer')
                continue
        pure = layer in ('domain', 'application') or path.startswith('lib/core/application/')
        for uri in imports(source):
            target = local_path(path, uri)
            target_context, target_layer = context_layer(target or '')
            def reject(condition, message):
                if condition:
                    errors.append(f'{path}: {message}: {uri}')

            reject(pure and uri.startswith(UI_PACKAGES + IMPLEMENTATION_PACKAGES + ('dart:io', 'dart:ui', 'dart:ffi')),
                   'framework or platform dependency in pure layer')
            reject(layer == 'presentation' and uri.startswith(IMPLEMENTATION_PACKAGES + ('dart:io', 'dart:ffi')),
                   'transport or device implementation in presentation')
            reject(path.startswith('lib/core/') and target_context is not None,
                   'foundation depends on business context')
            reject((pure or layer == 'presentation') and target is not None and target.startswith('lib/core/network/'),
                   'transport dependency outside infrastructure')
            reject(pure and target is not None and target.startswith(('lib/core/presentation/', 'lib/core/local/', 'lib/core/device/', 'lib/core/infrastructure/')),
                   'implementation dependency in pure layer')
            reject(layer == 'presentation' and target is not None and target.startswith(('lib/core/local/', 'lib/core/device/', 'lib/core/infrastructure/')),
                   'device or persistence dependency in presentation')
            reject(target_context is not None and context is not None and target_context != context and target_layer != 'application',
                   'foreign context dependency outside application contract')
            reject(layer == 'domain' and target_context is not None and (target_context != context or target_layer != 'domain'),
                   'domain depends on another layer or context')
            reject(layer == 'application' and target_layer in ('infrastructure', 'presentation'),
                   'application depends on implementation')
            reject(layer == 'presentation' and target_layer == 'infrastructure',
                   'presentation depends on infrastructure')
            reject(layer == 'infrastructure' and target_layer == 'presentation',
                   'infrastructure depends on presentation')
    return errors


def main():
    sources = {path.relative_to(ROOT).as_posix(): path.read_text()
               for path in (ROOT / 'lib').rglob('*.dart')}
    errors = inspect_sources(sources)
    if errors:
        print('\n'.join(errors))
        return 1
    print(f'Buyer context import boundaries valid ({len(sources)} Dart sources).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
