// Home's Storage card: "Show free space" swaps the library bar for the whole disk (CSS does the
// swap). This only remembers the box in this browser.
const KEY = "mediasync.showFree";
const box = document.getElementById("show-free");

if (box) {
    try {
        box.checked = localStorage.getItem(KEY) === "1";
    } catch (e) { /* no storage: the box starts unticked */ }
    box.addEventListener("change", () => {
        try {
            localStorage.setItem(KEY, box.checked ? "1" : "0");
        } catch (e) { /* not remembered, still shown */ }
    });
}
