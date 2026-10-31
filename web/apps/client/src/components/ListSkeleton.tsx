import { CardGroup } from "./Card.js";
import { ListRow } from "./ListRow.js";
import { useI18n } from "../i18n/I18nProvider.js";
import "./primitives.css";

export interface ListSkeletonProps {
  /** 骨架分组数，默认 3。 */
  readonly groups?: number;
  /** 每组行数，默认 3。 */
  readonly rows?: number;
  /** 无障碍文案；不传按当前语言取词条（common.loading）。 */
  readonly label?: string;
}

/**
 * 列表骨架屏（请求中占位，避免空白）。
 *
 * 对应 Compose 的 MuseListSkeleton：分组标题 + 卡片行，行内是扫光的
 * 标题条(55%) 与说明条(35%)。条高取 listRowVertical(14) / auxGap(10)，
 * 扫光用 loopExtraSlow(1800ms)；`prefers-reduced-motion` 下只留静态底色。
 */
export function ListSkeleton({ groups = 3, rows = 3, label }: ListSkeletonProps) {
  const { t } = useI18n();
  return (
    <div className="muse-stack" role="status" aria-label={label ?? t("common.loading")}>
      {Array.from({ length: groups }, (_, groupIndex) => (
        <CardGroup key={groupIndex} title={<span className="muse-skeleton is-label" />}>
          {Array.from({ length: rows }, (_, rowIndex) => (
            <ListRow
              key={rowIndex}
              divider={rowIndex < rows - 1}
              title={<span className="muse-skeleton" />}
              subtitle={<span className="muse-skeleton is-thin" />}
            />
          ))}
        </CardGroup>
      ))}
    </div>
  );
}
