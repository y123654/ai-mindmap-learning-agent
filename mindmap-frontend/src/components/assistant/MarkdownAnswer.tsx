import { Fragment, ReactNode } from "react";

function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let index = 0;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(<Fragment key={`${keyPrefix}-text-${index}`}>{text.slice(lastIndex, match.index)}</Fragment>);
    }

    const token = match[0];
    if (token.startsWith("`")) {
      nodes.push(
        <code key={`${keyPrefix}-code-${index}`} className="md-inline-code">
          {token.slice(1, -1)}
        </code>
      );
    } else {
      nodes.push(
        <strong key={`${keyPrefix}-strong-${index}`}>
          {token.slice(2, -2)}
        </strong>
      );
    }

    lastIndex = pattern.lastIndex;
    index += 1;
  }

  if (lastIndex < text.length) {
    nodes.push(<Fragment key={`${keyPrefix}-tail`}>{text.slice(lastIndex)}</Fragment>);
  }
  return nodes;
}

const FENCE_RE = /^\s*```/;
const HEADING_RE = /^(#{1,4})(?:\s+(.*))?$/;
const QUOTE_RE = /^\s*>/;
const LIST_RE = /^\s*([-*]|\d+\.)\s+/;
const TABLE_SEPARATOR_RE = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/;
const TABLE_ROW_RE = /\|/;

function isPlainParagraphLine(line: string): boolean {
  return Boolean(line.trim())
    && !FENCE_RE.test(line)
    && !HEADING_RE.test(line)
    && !QUOTE_RE.test(line)
    && !LIST_RE.test(line);
}

function splitTableRow(line: string): string[] {
  const trimmed = line.trim();
  // 去掉首尾各一个管道符，再按 | 拆分；单元格内部继续按纯文本处理。
  const withoutOuter = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  if (withoutOuter === "" && trimmed !== "|") {
    return [];
  }
  return withoutOuter.split("|").map((cell) => cell.trim());
}

type TableAlign = "left" | "center" | "right";

function parseTableAligns(separatorLine: string, count: number): TableAlign[] {
  const cells = splitTableRow(separatorLine);
  const aligns: TableAlign[] = [];
  for (let i = 0; i < count; i += 1) {
    const cell = (cells[i] ?? "").trim();
    const left = cell.startsWith(":");
    const right = cell.endsWith(":");
    if (left && right) aligns.push("center");
    else if (right) aligns.push("right");
    else aligns.push("left");
  }
  return aligns;
}

function MarkdownTable({ header, aligns, rows, tableKey }: {
  header: string[];
  aligns: TableAlign[];
  rows: string[][];
  tableKey: string;
}) {
  const columnCount = header.length;
  const renderCell = (cells: string[], cellIndex: number) => {
    const raw = cells[cellIndex] ?? "";
    return <Fragment key={cellIndex}>{raw ? renderInline(raw, `${tableKey}-cell-${cellIndex}`) : ""}</Fragment>;
  };

  return (
    <div className="md-table-wrap" key={tableKey}>
      <table className="md-table">
        <thead>
          <tr>
            {Array.from({ length: columnCount }, (_, index) => (
              <th key={index} style={{ textAlign: aligns[index] }}>
                {renderCell(header, index)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {Array.from({ length: columnCount }, (_, index) => (
                <td key={index} style={{ textAlign: aligns[index] }}>
                  {renderCell(row, index)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function MarkdownAnswer({ content }: { content: string }) {
  const blocks: ReactNode[] = [];
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  let i = 0;
  let key = 0;

  while (i < lines.length) {
    const startIndex = i;
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    if (FENCE_RE.test(line)) {
      const language = line.trimStart().slice(3).trim();
      const codeLines: string[] = [];
      i += 1;
      while (i < lines.length && !FENCE_RE.test(lines[i])) {
        codeLines.push(lines[i]);
        i += 1;
      }
      if (i < lines.length) i += 1;
      blocks.push(
        <div className="md-code-block" key={`code-${key++}`}>
          {language && <div className="md-code-language">{language}</div>}
          <pre><code>{codeLines.join("\n")}</code></pre>
        </div>
      );
      continue;
    }

    const heading = line.match(HEADING_RE);
    if (heading) {
      const level = heading[1].length;
      const headingText = (heading[2] ?? "").trim();
      const className = `md-heading md-h${level}`;
      blocks.push(
        <div className={className} key={`heading-${key++}`}>
          {headingText ? renderInline(headingText, `heading-${key}`) : ""}
        </div>
      );
      i += 1;
      continue;
    }

    if (QUOTE_RE.test(line)) {
      const quoteLines: string[] = [];
      while (i < lines.length && QUOTE_RE.test(lines[i])) {
        quoteLines.push(lines[i].trimStart().replace(/^>\s?/, ""));
        i += 1;
      }
      blocks.push(
        <blockquote className="md-quote" key={`quote-${key++}`}>
          {quoteLines.map((item, index) => (
            <p key={index}>{renderInline(item, `quote-${key}-${index}`)}</p>
          ))}
        </blockquote>
      );
      continue;
    }

    if (LIST_RE.test(line)) {
      const ordered = /^\s*\d+\.\s+/.test(line);
      const items: string[] = [];
      while (i < lines.length && LIST_RE.test(lines[i])) {
        items.push(lines[i].replace(/^\s*([-*]|\d+\.)\s+/, ""));
        i += 1;
      }
      const ListTag = ordered ? "ol" : "ul";
      blocks.push(
        <ListTag className="md-list" key={`list-${key++}`}>
          {items.map((item, index) => (
            <li key={index}>{renderInline(item, `list-${key}-${index}`)}</li>
          ))}
        </ListTag>
      );
      continue;
    }

    // GFM 表格：本行像表头（含 |），且下一行是 | --- | --- | 分隔行。
    // 流式输出时分隔行还没到达，则按普通文本先行展示，下一帧自动升级为表格。
    if (TABLE_ROW_RE.test(line) && i + 1 < lines.length && TABLE_SEPARATOR_RE.test(lines[i + 1])) {
      const tableLines: string[] = [line];
      let j = i + 1;
      while (j < lines.length && TABLE_ROW_RE.test(lines[j])) {
        tableLines.push(lines[j]);
        j += 1;
      }
      const header = splitTableRow(tableLines[0]);
      if (header.length > 0) {
        const aligns = parseTableAligns(tableLines[1], header.length);
        const rows = tableLines.slice(2).map(splitTableRow);
        const tableKey = `table-${key++}`;
        blocks.push(
          <MarkdownTable
            key={tableKey}
            tableKey={tableKey}
            header={header}
            aligns={aligns}
            rows={rows}
          />
        );
        i = j;
        continue;
      }
    }

    // 普通段落：用 do/while 保证至少消费当前行，避免任何分支“不前进”造成死循环。
    const paragraphLines: string[] = [];
    do {
      paragraphLines.push(lines[i].trim());
      i += 1;
    } while (i < lines.length && isPlainParagraphLine(lines[i]));
    blocks.push(
      <p className="md-paragraph" key={`paragraph-${key++}`}>
        {paragraphLines.map((item, index) => (
          <Fragment key={index}>
            {index > 0 && <br />}
            {renderInline(item, `paragraph-${key}-${index}`)}
          </Fragment>
        ))}
      </p>
    );

    // 防御性兜底：理论上不会走到；若未来新增分支漏推进行号，也不能让页面死循环崩溃。
    if (i === startIndex) {
      i += 1;
    }
  }

  return <div className="markdown-answer">{blocks}</div>;
}
