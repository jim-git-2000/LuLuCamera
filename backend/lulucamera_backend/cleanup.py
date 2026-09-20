from __future__ import annotations

import shutil
from pathlib import Path

from .tasks import repository


def main() -> None:
    for job in repository.expire_due():
        shutil.rmtree(Path(job.original_path).parent, ignore_errors=True)


if __name__ == "__main__":
    main()
