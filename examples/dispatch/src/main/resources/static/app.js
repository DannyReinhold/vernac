"use strict";
const $ = (id) => document.getElementById(id);
let board,
  submitAction,
  busy = false,
  refreshTimer;
const node = (tag, text, cls) => {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  if (cls) n.className = cls;
  return n;
};
function notice(message, error = false) {
  const n = $("notice");
  n.textContent = message;
  n.className = error ? "error" : "";
  n.hidden = false;
  clearTimeout(n.timer);
  n.timer = setTimeout(() => (n.hidden = true), 6500);
}
async function request(path, body) {
  const response = await fetch(
    "/api" + path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(
      error.message ||
        "The request could not be completed. Refresh and try again.",
    );
  }
  return response.status === 204
    ? null
    : response.text().then((t) => (t ? JSON.parse(t) : null));
}
async function act(path, body = {}) {
  if (busy) return;
  busy = true;
  try {
    await request(path, body);
    await refresh();
    notice("Done. The board reflects the committed state.");
  } catch (e) {
    notice(e.message, true);
  } finally {
    busy = false;
  }
}
function button(text, fn, cls = "secondary") {
  const b = node("button", text, cls);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}
function field(name, label, type = "text", value = "") {
  const l = node("label", label);
  const i = node("input");
  i.name = name;
  i.type = type;
  i.value = value;
  i.required = true;
  if (type === "number") {
    i.min = "1";
    i.max = "1000000";
    i.step = "1";
  } else {
    i.maxLength = 160;
  }
  l.append(i);
  $("fields").append(l);
}
function dialog(title, setup, action) {
  $("dialog-title").textContent = title;
  $("fields").replaceChildren();
  $("form-error").textContent = "";
  setup();
  submitAction = action;
  $("editor").showModal();
}
$("close-dialog").onclick = () => $("editor").close();
$("editor-form").onsubmit = async (e) => {
  e.preventDefault();
  const b = e.submitter;
  b.disabled = true;
  try {
    await submitAction(Object.fromEntries(new FormData(e.target)));
    $("editor").close();
    await refresh();
    notice("Saved. Business rules and persistence checks passed.");
  } catch (err) {
    $("form-error").textContent = err.message;
  } finally {
    b.disabled = false;
  }
};
$("new-tour").onclick = () =>
  dialog(
    "Plan a new tour",
    () => {
      field("title", "Tour title");
      field("capacity", "Capacity in crates", "number", 18);
    },
    (d) => request("/tours", { title: d.title, capacity: Number(d.capacity) }),
  );
$("fail-next").onclick = async () => {
  try {
    await request("/carrier/fail-next", {});
    notice("One outage armed. Dispatch a ready tour to see the retry.");
  } catch (e) {
    notice(e.message, true);
  }
};
$("search").oninput = () => {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, 250);
};
function render() {
  $("tour-count").textContent = board.tours.length;
  $("load-count").textContent = board.tours.reduce((s, t) => s + t.load, 0);
  $("receipt-count").textContent = board.receipts.length;
  $("tours").replaceChildren();
  for (const tour of board.tours) {
    const card = node("article", undefined, "tour"),
      head = node("div", undefined, "tour-head");
    head.append(
      node("span", tour.state, "badge " + tour.state),
      node("h3", tour.title),
    );
    const load = node("div", undefined, "load");
    load.append(
      node("span", tour.stops.length + " stops"),
      node("span", tour.load + " / " + tour.capacity + " crates"),
    );
    const meter = node("progress");
    meter.max = tour.capacity;
    meter.value = tour.load;
    meter.setAttribute("aria-label", "Capacity used");
    head.append(load, meter);
    card.append(head);
    tour.stops.forEach((stop, index) => {
      const row = node("div", undefined, "stop"),
        content = node("div", undefined, "stop-content");
      content.append(
        node("strong", stop.customer),
        node("p", stop.street + " · " + stop.city),
        node("p", stop.units + " crates", "crates"),
      );
      row.append(
        node("span", String(index + 1).padStart(2, "0"), "number"),
        content,
      );
      if (tour.state === "PLANNED") {
        const b = button(
          "↗",
          () =>
            dialog(
              "Move " + stop.customer,
              () => {
                const label = node("label", "Target tour"),
                  select = node("select");
                select.name = "target";
                select.required = true;
                const placeholder = node("option", "Choose a planned tour");
                placeholder.value = "";
                select.append(placeholder);
                board.tours
                  .filter((t) => t.id !== tour.id && t.state === "PLANNED")
                  .forEach((t) => {
                    const o = node(
                      "option",
                      t.title + " · " + (t.capacity - t.load) + " free",
                    );
                    o.value = t.id;
                    select.append(o);
                  });
                label.append(select);
                $("fields").append(label);
              },
              (d) =>
                request("/move", {
                  source: tour.id,
                  target: d.target,
                  stop: stop.id,
                }),
            ),
          "transfer",
        );
        b.setAttribute("aria-label", "Move " + stop.customer);
        row.append(b);
      }
      card.append(row);
    });
    if (!tour.stops.length)
      card.append(node("p", "Add your first delivery stop.", "empty"));
    const actions = node("div", undefined, "tour-actions");
    if (tour.state === "PLANNED") {
      actions.append(
        button("+ Add stop", () =>
          dialog(
            "Add a delivery",
            () => {
              field("customer", "Customer");
              field("street", "Street");
              field("city", "City", "text", "Bremen");
              field("units", "Crates", "number", 1);
            },
            (d) =>
              request("/tours/" + tour.id + "/stops", {
                ...d,
                units: Number(d.units),
              }),
          ),
        ),
      );
      actions.append(
        button(
          "Release tour",
          () => act("/tours/" + tour.id + "/release"),
          "primary",
        ),
      );
    } else if (tour.state === "READY")
      actions.append(
        button(
          "Dispatch tour →",
          () => act("/tours/" + tour.id + "/dispatch"),
          "primary",
        ),
      );
    else
      actions.append(
        node(
          "span",
          "✓ Plan committed · carrier notified asynchronously",
          "muted",
        ),
      );
    card.append(actions);
    $("tours").append(card);
  }
  if (!board.tours.length)
    $("tours").append(
      node(
        "p",
        "No matching tours. Try another search or plan a new tour.",
        "empty",
      ),
    );
  $("jobs").replaceChildren();
  for (const job of board.deliveries) {
    const r = node("div", undefined, "job"),
      left = node("div", "Carrier notification");
    left.append(node("small", "Attempt " + job.attempts));
    r.append(left, node("span", job.status, "badge"));
    $("jobs").append(r);
  }
  $("receipts").replaceChildren();
  for (const receipt of board.receipts) {
    const r = node("div", undefined, "receipt"),
      left = node("div", receipt.title);
    left.append(node("small", receipt.quote_reference));
    r.append(
      left,
      node(
        "strong",
        Number(receipt.amount).toFixed(2) + " " + receipt.currency,
      ),
    );
    $("receipts").append(r);
  }
  if (!board.deliveries.length && !board.receipts.length)
    $("jobs").append(
      node("p", "Dispatch a tour to start its delivery story.", "empty"),
    );
}
let loading = false;
async function refresh() {
  if (loading) return;
  loading = true;
  try {
    board = await request(
      "/board?search=" + encodeURIComponent($("search").value),
    );
    render();
  } catch (e) {
    notice(e.message, true);
  } finally {
    loading = false;
  }
}
refresh();
setInterval(() => {
  if (!document.hidden && !$("editor").open && !busy) refresh();
}, 2000);
