import type { Project } from './bridge';

const escape = (value: string) =>
  value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

/** The palette reads the consumer's contracts; it never invents a project component API. */
export function componentMarkup(
  name: string,
  id: string,
  schema: Project['profile']['schema'],
): string {
  const builtin = schema.builtins[name];
  const attributes: Record<string, string> = { id, width: '120', height: '18' };
  if (builtin) {
    Object.assign(attributes, builtin.defaults);
    if (builtin.attributes.includes('label')) attributes.label = `New ${name}`;
    if (builtin.attributes.includes('action')) attributes.action = id;
    if (name === 'item') attributes.size = '18';
  } else {
    const contract = schema.components[name];
    if (!contract) throw new Error(`Unknown component: ${name}`);
    attributes.name = name;
    for (const [key, property] of Object.entries(contract.scalars)) {
      if (property.defaultValue != null) attributes[key] = property.defaultValue;
      else if (property.required) {
        attributes[key] =
          property.type === 'BOOLEAN'
            ? 'false'
            : property.type === 'INTEGER' || property.type === 'DECIMAL'
              ? String(property.min)
              : property.type === 'ENUM'
                ? property.choices[0]
                : property.type === 'COLOR'
                  ? '#FFFFFF'
                  : property.type === 'RESOURCE'
                    ? 'project:asset'
                    : '';
      }
    }
    for (const [key, value] of Object.entries(contract.values)) {
      if (value.required) attributes[key] = `{{${key}}}`;
    }
  }
  const tag = builtin ? `dui-${name}` : 'dui-component';
  const attrs = Object.entries(attributes)
    .map(([key, value]) => ` ${key}="${escape(value)}"`)
    .join('');
  if (name === 'dropdown')
    return `<${tag}${attrs}><dui-option label="Option one" value="one"/><dui-option label="Option two" value="two"/></${tag}>`;
  if (['row', 'column', 'grid', 'panel', 'layer', 'group'].includes(name))
    return `<${tag}${attrs}>\n  </${tag}>`;
  return `<${tag}${attrs}/>`;
}
