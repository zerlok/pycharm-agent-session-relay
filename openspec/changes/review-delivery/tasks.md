## 1. Write REVIEW.md

- [ ] 1.1 Add a "Submit review" action that serializes the batch (via `review-export`) and writes `REVIEW.md` at the project base path
- [ ] 1.2 Run the file write on a background thread (`Task.Backgroundable` / pooled executor), never the EDT
- [ ] 1.3 Empty batch: write nothing and inform the user there is nothing to submit

## 2. Notify

- [ ] 2.1 After a successful write, notify the user that `REVIEW.md` is ready at the project root
- [ ] 2.2 Do not open any agent connection or type into a terminal in this change

## 3. Clear the batch (via the comment-batch store's clear)

- [ ] 3.1 After a successful write, clear the pending batch (store, gutter markers, tool window)
- [ ] 3.2 On a failed write, leave the batch intact

## 4. Verification

- [ ] 4.1 Manually verify in `runIde`: author comments → Submit writes `REVIEW.md` with `@path#L` refs → notification shown → batch cleared; empty submit writes nothing and informs the user
- [ ] 4.2 Confirm the UI stays responsive during submit (write is off the EDT)
