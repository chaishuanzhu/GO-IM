import UIKit
import Domain

@MainActor
public final class SearchViewController: UITableViewController, UISearchResultsUpdating, UISearchBarDelegate {
    private let env: AppEnvironment
    private let viewModel: SearchViewModel
    private let searchController = UISearchController(searchResultsController: nil)

    public init(env: AppEnvironment) {
        self.env = env
        viewModel = SearchViewModel(env: env)
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "搜索"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always

        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchBar.placeholder = "搜索消息"
        searchController.searchBar.delegate = self
        searchController.searchBar.returnKeyType = .search
        navigationItem.searchController = searchController
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true

        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        viewModel.onChange = { [weak self] in
            self?.tableView.reloadData()
            self?.updateEmpty()
        }
        updateEmpty()
    }

    private func updateEmpty() {
        if viewModel.hits.isEmpty {
            let label = UILabel()
            label.text = viewModel.query.isEmpty ? "输入关键词搜索聊天记录" : "未找到相关消息"
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            label.numberOfLines = 0
            label.font = .preferredFont(forTextStyle: .body)
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
    }

    public func updateSearchResults(for searchController: UISearchController) {
        viewModel.query = searchController.searchBar.text ?? ""
    }

    public func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
        viewModel.query = searchBar.text ?? ""
        Task { await viewModel.search() }
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        viewModel.hits.count
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let hit = viewModel.hits[indexPath.row]
        var config = cell.defaultContentConfiguration()
        config.text = hit.content
        config.textProperties.numberOfLines = 2
        config.secondaryText = "\(hit.fromUID)"
        config.secondaryTextProperties.color = .secondaryLabel
        config.image = UIImage(systemName: "text.bubble")
        config.imageProperties.tintColor = .systemBlue
        cell.contentConfiguration = config
        cell.accessoryType = .none
        cell.selectionStyle = .default
        return cell
    }
}
