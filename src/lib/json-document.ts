import { jsonLanguage } from '@codemirror/lang-json';
import type { SyntaxNode } from './document';

export function jsonValue(source: string, path: (string | number)[], value: unknown): string {
  const tree = jsonLanguage.parser.parse(source);
  let error = false;
  tree.iterate({
    enter(n) {
      if (n.type.isError) error = true;
    },
  });
  if (error) throw new Error('Fix JSON syntax before using the inspector.');
  let node: SyntaxNode | null = tree.topNode.firstChild;
  for (const key of path) {
    if (node?.name === 'Object') {
      const property = node
        .getChildren('Property')
        .find(
          (p) =>
            JSON.parse(
              source.slice(p.getChild('PropertyName')!.from, p.getChild('PropertyName')!.to),
            ) === key,
        );
      node = property?.lastChild ?? null;
    } else if (node?.name === 'Array' && typeof key === 'number') {
      const values = [];
      for (let n = node.firstChild; n; n = n.nextSibling)
        if (n.name !== '[' && n.name !== ']' && n.name !== ',') values.push(n);
      node = values[key] ?? null;
    } else node = null;
  }
  if (!node) throw new Error('JSON property does not exist. Add it in the source editor.');
  return source.slice(0, node.from) + JSON.stringify(value) + source.slice(node.to);
}
