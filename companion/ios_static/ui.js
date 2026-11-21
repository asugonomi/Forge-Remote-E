export function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}
export function button(text, action, style) {
  const node = element("button", text, style);
  node.type = "button";
  node.addEventListener("click", async () => {
    try { await action(); } catch (error) { notify(error.message || "操作に失敗しました"); }
  });
  return node;
}
export function section(title, ...items) {
  const box = element("section", null, "section");
  if (title) box.append(element("h2", title));
  box.append(...items);
  return box;
}
export function group(title, ...items) {
  const box = element("details", null, "group");
  box.append(element("summary", title), ...items);
  return box;
}
export function row(...items) {
  const box = element("div", null, "actions");
  box.append(...items);
  return box;
}
export function fields(...items) {
  const box = element("div", null, "fields");
  box.append(...items);
  return box;
}
export function field(label, value, change, options = {}) {
  const box = element("label", null, "field");
  const input = element(options.choices ? "select" : options.long ? "textarea" : "input");
  if (options.choices) {
    const choices = [...new Set([value,...options.choices].filter(v => v != null && v !== ""))];
    for (const val of choices) input.add(new Option(val, val));
  } else if (input.tagName === "INPUT") input.type = options.type || "text";
  for (const key of ["min","max","step"]) if (options[key] != null) input[key] = options[key];
  input.value = value ?? "";
  if (options.type === "checkbox") {
    input.checked = Boolean(value);
    box.classList.add("check");
    input.addEventListener("change", () => change(input.checked));
    box.append(input, document.createTextNode(label));
  } else {
    input.addEventListener(options.choices ? "change" : "input", () => change(
      options.type === "number" && input.value !== "" ? Number(input.value) : input.value));
    box.append(element("span", label), input);
  }
  return box;
}
export function slider(label, val, min, max, step, change) {
  const box = element("div", null, "field");
  const range = element("input"), number = element("input");
  range.type = "range"; number.type = "number";
  for (const control of [range, number]) {
    Object.assign(control, {min, max, step, value: val});
    control.setAttribute("aria-label", label + (control === range ? " スライダー" : ""));
    control.addEventListener("input", () => {
      if (!control.value || !control.validity.valid) return;
      const result = Number(control.value);
      (control === range ? number : range).value = result;
      change(result);
    });
  }
  const line = element("div", null, "slider"); line.append(range, number);
  box.append(element("span", label), line);
  return box;
}
let noticeTimer;
export function notify(message) {
  const node = document.querySelector("#notice");
  node.textContent = message; node.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { node.hidden = true; }, 4800);
}
export function readImage(file) {
  if (!file || file.size > 42 * 1024 * 1024) throw Error("42 MB以下の画像を選んでください");
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(Error("画像を読み込めませんでした"));
    reader.readAsDataURL(file);
  });
}
