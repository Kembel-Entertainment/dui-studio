import { parser } from '@lezer/xml';
export type SyntaxNode = ReturnType<typeof parser.parse>['topNode'];

export type Attribute = {
  name: string;
  value: string;
  from: number;
  to: number;
  valueFrom: number;
  valueTo: number;
  quote: string;
};
export type Element = {
  path: string;
  tag: string;
  from: number;
  to: number;
  openTo: number;
  closeFrom: number;
  selfClosing: boolean;
  attributes: Attribute[];
  children: Element[];
};
export type Edit = { from: number; to: number; text: string };
const decode = (s: string) =>
  s
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
const encode = (s: string, quote: string) =>
  s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(new RegExp(quote, 'g'), quote === '"' ? '&quot;' : '&apos;');

/** A concrete syntax tree supplies spans. Never serialize XML to perform a visual edit. */
export function parse(source: string): { roots: Element[]; errors: number[] } {
  const tree = parser.parse(source),
    errors: number[] = [];
  tree.iterate({
    enter(n) {
      if (n.type.isError) errors.push(n.from);
    },
  });
  if (errors.length) return { roots: [], errors };
  function element(n: SyntaxNode, path: string): Element {
    const open = n.getChild('OpenTag') ?? n.getChild('SelfClosingTag')!;
    const close = n.getChild('CloseTag');
    const attributes = open.getChildren('Attribute').map((a) => {
      const name = a.getChild('AttributeName')!,
        value = a.getChild('AttributeValue')!;
      const raw = source.slice(value.from, value.to),
        quote = raw[0];
      return {
        name: source.slice(name.from, name.to),
        value: decode(raw.slice(1, -1)),
        from: a.from,
        to: a.to,
        valueFrom: value.from + 1,
        valueTo: value.to - 1,
        quote,
      };
    });
    return {
      path,
      tag: source.slice(open.getChild('TagName')!.from, open.getChild('TagName')!.to),
      from: n.from,
      to: n.to,
      openTo: open.to,
      closeFrom: close?.from ?? open.to,
      selfClosing: !close,
      attributes,
      children: n.getChildren('Element').map((c, i) => element(c, `${path}/${i}`)),
    };
  }
  return { roots: tree.topNode.getChildren('Element').map((n, i) => element(n, `${i}`)), errors };
}
export function flatten(roots: Element[]): Element[] {
  return roots.flatMap((n) => [n, ...flatten(n.children)]);
}
export function attr(n: Element, name: string, fallback = '') {
  return n.attributes.find((a) => a.name === name)?.value ?? fallback;
}
export function apply(source: string, edits: Edit[]): string {
  let limit = source.length;
  for (const e of [...edits].sort((a, b) => b.from - a.from)) {
    if (e.to > limit || e.from > e.to) throw new Error('Overlapping document edits');
    source = source.slice(0, e.from) + e.text + source.slice(e.to);
    limit = e.from;
  }
  return source;
}
export function setAttributes(
  source: string,
  n: Element,
  values: Record<string, string | null>,
): string {
  const edits: Edit[] = [];
  const additions: string[] = [];
  for (const [name, value] of Object.entries(values)) {
    if (!/^[a-zA-Z_][a-zA-Z0-9_.:-]*$/.test(name)) throw new Error('Invalid XML attribute');
    const old = n.attributes.find((a) => a.name === name);
    if (old && value === null) edits.push({ from: old.from, to: old.to, text: '' });
    else if (old && value !== null)
      edits.push({ from: old.valueFrom, to: old.valueTo, text: encode(value, old.quote) });
    else if (value !== null) additions.push(` ${name}="${encode(value, '"')}"`);
  }
  if (additions.length)
    edits.push({
      from: n.openTo - (n.selfClosing ? 2 : 1),
      to: n.openTo - (n.selfClosing ? 2 : 1),
      text: additions.join(''),
    });
  return apply(source, edits);
}
export function duplicate(source: string, nodes: Element[]): string {
  const all = flatten(parse(source).roots),
    ids = new Set(all.map((n) => attr(n, 'id')).filter(Boolean));
  const roots = nodes.filter((n) => !nodes.some((p) => n.path.startsWith(p.path + '/')));
  return apply(
    source,
    roots.map((n) => {
      let fragment = source.slice(n.from, n.to);
      const edits: Edit[] = [];
      for (const child of flatten([n])) {
        const id = child.attributes.find((a) => a.name === 'id');
        if (!id) continue;
        let copy = id.value + '_copy';
        let i = 2;
        while (ids.has(copy)) copy = id.value + '_copy' + i++;
        ids.add(copy);
        edits.push({
          from: id.valueFrom - n.from,
          to: id.valueTo - n.from,
          text: encode(copy, id.quote),
        });
      }
      fragment = apply(fragment, edits);
      return { from: n.to, to: n.to, text: '\n' + fragment };
    }),
  );
}
export function group(source: string, nodes: Element[]): string {
  if (!nodes.length) return source;
  const parent = nodes[0].path.split('/').slice(0, -1).join('/');
  if (!parent || nodes.some((n) => n.path.split('/').slice(0, -1).join('/') !== parent))
    throw new Error('Group elements within the same parent');
  const sorted = [...nodes].sort((a, b) => a.from - b.from);
  const indices = sorted.map((n) => Number(n.path.split('/').at(-1)));
  if (indices.at(-1)! - indices[0] + 1 !== indices.length)
    throw new Error('Select consecutive sibling elements to retain painter order');
  return apply(source, [
    {
      from: sorted[0].from,
      to: sorted.at(-1)!.to,
      text: '<dui-group>\n' + source.slice(sorted[0].from, sorted.at(-1)!.to) + '\n</dui-group>',
    },
  ]);
}
export class History {
  past: string[] = [];
  future: string[] = [];
  push(previous: string) {
    this.past.push(previous);
    if (this.past.length > 100) this.past.shift();
    this.future = [];
  }
  undo(current: string) {
    const value = this.past.pop();
    if (value === undefined) return current;
    this.future.push(current);
    return value;
  }
  redo(current: string) {
    const value = this.future.pop();
    if (value === undefined) return current;
    this.past.push(current);
    return value;
  }
}
